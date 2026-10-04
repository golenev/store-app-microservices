"""Запускает все Kotlin E2E в отдельном Compose-проекте с новыми томами и автоматически выбранными портами."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


def free_ports(count):
    """Выбирает разные локальные порты, временно удерживая их сокетами. Если после освобождения порт займёт другой процесс, запуск Compose завершится ошибкой."""
    sockets = []
    try:
        for _ in range(count):
            server = socket.socket()
            server.bind(("127.0.0.1", 0))
            sockets.append(server)
        return [server.getsockname()[1] for server in sockets]
    finally:
        for server in sockets:
            server.close()


def main():
    """Собирает и запускает изолированные сервисы, выполняет Kotlin E2E и сохраняет диагностику. Останавливает только свои контейнеры, сохраняя тома; возвращает код завершения тестов."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-jars", action="store_true", help="Использовать готовые Maven JAR в образе Java 21; без этого параметра сервисы собираются из Dockerfile")
    args = parser.parse_args()
    project = "shop-e2e-" + uuid.uuid4().hex[:12]
    output = ROOT / "target" / "e2e" / project
    output.mkdir(parents=True)
    env = os.environ.copy()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain", "--", "e2e-tests", "scripts/run-e2e.py"], cwd=ROOT, text=True).strip()
    if dirty:
        revision += "-dirty"
    test_sources = [Path(__file__).resolve(), ROOT / "e2e-tests" / "build.gradle.kts"]
    test_sources += sorted(path for path in (ROOT / "e2e-tests" / "src").rglob("*") if path.is_file())
    manifest = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in test_sources}
    manifest_json = json.dumps(manifest, sort_keys=True, indent=2)
    (output / "test-source-manifest.json").write_text(manifest_json, encoding="utf-8")
    ports = dict(zip(["POSTGRES_PORT", "KAFKA_PORT", "REDIS_PORT", "STORE_PORT", "TARIFFS_PORT", "WAREHOUSE_PORT"], free_ports(6)))
    env.update({key: str(value) for key, value in ports.items()})
    env.update({"KAFKA_HOST": "localhost", "E2E_COMPOSE_PROJECT": project, "E2E_POSTGRES_PORT": env["POSTGRES_PORT"], "E2E_KAFKA": "localhost:" + env["KAFKA_PORT"], "E2E_REDIS_PORT": env["REDIS_PORT"],
                "E2E_ALLURE_RESULTS": str(output / "allure-results"), "E2E_REVISION": revision,
                "E2E_TEST_SOURCE_HASH": hashlib.sha256(manifest_json.encode("utf-8")).hexdigest(),
                "E2E_STORE_URL": "http://localhost:" + env["STORE_PORT"], "E2E_TARIFFS_URL": "http://localhost:" + env["TARIFFS_PORT"], "E2E_WAREHOUSE_URL": "http://localhost:" + env["WAREHOUSE_PORT"]})
    # Явные адреса не позволяют унаследованным настройкам направить тестовый клиент в чужое окружение.
    env["WAREHOUSE_PUBLIC_URL"] = env["E2E_WAREHOUSE_URL"]
    env["SHOP_UI_ORIGINS"] = env["E2E_STORE_URL"]
    command = ["docker", "compose", "-p", project, "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "compose.e2e.yml")]
    if args.runtime_jars:
        override = output / "runtime-jars.yml"
        lines = ["services:"]
        for module in ["store-service", "tariffs-service", "warehouse-service"]:
            jar = ROOT / module / "target" / (module + "-0.0.1-SNAPSHOT.jar")
            if not jar.is_file():
                parser.error("Build first: mvn -B -ntp -DskipTests package; missing " + str(jar))
            lines += ["  " + module + ":", "    build: !reset null", "    image: eclipse-temurin:21-jre-jammy",
                      '    entrypoint: [java, -jar, /app/application.jar]', "    volumes:",
                      "      - " + json.dumps(jar.as_posix() + ":/app/application.jar:ro")]
        override.write_text("\n".join(lines) + "\n", encoding="utf-8")
        env["E2E_EXTRA_COMPOSE_FILE"] = str(override)
        command += ["-f", str(override)]
    else:
        env.pop("E2E_EXTRA_COMPOSE_FILE", None)
    (output / "environment.json").write_text(json.dumps({key: value for key, value in env.items() if key.startswith("E2E_")}, indent=2), encoding="utf-8")
    print("Owned E2E project:", project, "STORE:", env["E2E_STORE_URL"], flush=True)
    result = 1
    try:
        subprocess.run(command + ["--profile", "apps", "up", "-d", "--no-build" if args.runtime_jars else "--build", "--wait", "--wait-timeout", "180"], cwd=ROOT, env=env, check=True)
        wrapper = ROOT / "e2e-tests" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        executable = [str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]
        gradle_options = ["--no-daemon"]
        if env.get("E2E_GRADLE_INIT_SCRIPT"):
            gradle_options += ["--init-script", str(Path(env["E2E_GRADLE_INIT_SCRIPT"]).resolve())]
        result = subprocess.run(executable + gradle_options + ["test"], cwd=ROOT / "e2e-tests", env=env).returncode
    finally:
        with (output / "compose.log").open("w", encoding="utf-8") as log:
            subprocess.run(command + ["--profile", "apps", "logs", "--no-color"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        # Перед остановкой снимаем паузу со своих контейнеров. Обычное окружение shop-runtime эта команда не затрагивает.
        subprocess.run(command + ["unpause"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(command + ["--profile", "apps", "down"], cwd=ROOT, env=env, check=True)
        print("Diagnostics:", output, "Volumes retained:", project, flush=True)
    return result


if __name__ == "__main__":
    sys.exit(main())
