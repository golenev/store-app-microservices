"""Проверяет размещение production-классов и явные зависимости слоёв трёх backend-сервисов."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SERVICES = {
    "store-service": "com.shop.store",
    "warehouse-service": "com.shop.warehouse",
    "tariffs-service": "com.tariffs",
}
ALLOWED = {
    "controller": {"service", "codec", "dto", "model", "exception"},
    "service": {"service", "repository", "client", "messaging", "messaging.dto", "codec", "dto", "model", "exception", "validation"},
    "repository": {"repository", "codec", "dto", "model", "exception", "validation", "messaging.dto"},
    "messaging": {"service", "dto", "model", "exception", "messaging"},
    "config": {"config"},
    "client": {"client", "codec", "dto", "model", "exception"},
    "codec": {"codec", "dto", "model", "exception", "validation", "messaging.dto"},
    "dto": {"dto", "model", "validation"},
    "model": {"model", "dto", "messaging.dto"},
    "exception": {"exception", "dto"},
    "validation": {"validation"},
    "messaging.dto": {"messaging.dto", "dto", "model"},
}


def layer_of(name, root):
    """Определяет слой локального типа name; контракт Kafka выделяет как messaging.dto."""
    relative = name.removeprefix(root + ".")
    if relative.startswith("messaging.dto."):
        return "messaging.dto"
    return relative.split(".")[0]


def check_source(path, root):
    """Проверяет пакет и явные импорты файла; возвращает нарушения без запуска приложения."""
    source = path.read_text(encoding="utf-8")
    package = re.search(r"^package ([\w.]+);", source, re.MULTILINE)
    if package is None:
        return ["не объявлен package"]
    package = package[1]
    expected = Path(*package.split("."), path.name)
    actual = path.relative_to(path.parents[len(package.split("."))])
    errors = []
    if actual != expected:
        errors.append("package не соответствует пути файла")
    if package == root:
        if not path.stem.endswith("ServiceApplication"):
            errors.append("в корневом пакете допускается только класс запуска")
        return errors
    layer = layer_of(package + "." + path.stem, root)
    if layer not in ALLOWED:
        return errors + [f"неизвестный слой {layer}"]
    for imported in re.findall(r"^import (?:static )?([^;]+);", source, re.MULTILINE):
        if imported.startswith(root + "."):
            target = layer_of(imported, root)
            if target not in ALLOWED[layer]:
                errors.append(f"{layer} не должен зависеть от {target}: {imported}")
        elif any(imported.startswith(other + ".") for other in SERVICES.values() if other != root):
            errors.append(f"зависимость от production-класса соседнего сервиса: {imported}")
        if imported.startswith(("org.springframework.jdbc.", "org.springframework.data.redis.")) and layer != "repository":
            errors.append(f"доступ к хранилищу вне repository: {imported}")
        if imported.startswith("org.springframework.kafka.") and layer not in {"messaging", "config"}:
            errors.append(f"Kafka вне messaging/config: {imported}")
        if imported.startswith("org.springframework.web.bind.annotation.") and layer not in {"controller", "exception"}:
            errors.append(f"HTTP-аннотации вне controller/exception: {imported}")
    declarations = re.findall(r"^\s*public (?:final )?(?:class|record|interface|enum) (\w+)", source, re.MULTILINE)
    if declarations != [path.stem]:
        errors.append("файл должен содержать один публичный тип с именем файла")
    if re.search(r"\bpublic record\b", source) and layer not in {"dto", "model", "messaging.dto"}:
        errors.append("record должен находиться в dto, model или messaging.dto")
    return errors


def main():
    """Проверяет все production-файлы; выводит нарушения и возвращает ненулевой код для CI."""
    checked = 0
    failures = []
    for service, root in SERVICES.items():
        for path in sorted((ROOT / service / "src/main/java").rglob("*.java")):
            checked += 1
            for message in check_source(path, root):
                failures.append(f"{path.relative_to(ROOT)}: {message}")
    if failures:
        print("\n".join(failures))
        return 1
    print(f"Backend layers: {checked} Java files checked; no violations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
