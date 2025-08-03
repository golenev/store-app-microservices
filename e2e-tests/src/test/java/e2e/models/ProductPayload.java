package e2e.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductPayload {
    private Long barcodeId;
    private String shortName;
    private String description;
    private BigDecimal price;
    private int quantity;
    private String addedAtWarehouse;
    @JsonProperty("isFoodstuff")
    private boolean isFoodstuff;
}
