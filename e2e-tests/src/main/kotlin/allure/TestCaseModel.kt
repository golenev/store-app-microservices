package allure

import com.fasterxml.jackson.annotation.JsonProperty

data class BatchRequest(
    @JsonProperty("items")
    val items: List<TestCaseItem>
)

data class TestCaseItem(
    @JsonProperty("testId")
    val testId: String,

    @JsonProperty("category")
    val category: String,

    @JsonProperty("shortTitle")
    val shortTitle: String,

    @JsonProperty("issueLink")
    val issueLink: String,

    @JsonProperty("readyDate")
    val readyDate: String,

    @JsonProperty("generalStatus")
    val generalStatus: String,

    @JsonProperty("scenario")
    val scenario: String,

    @JsonProperty("notes")
    val notes: String
)