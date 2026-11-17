package br.com.ieeeiffcampos.patrimonio;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;

public record NodeRequest(
        @Size(max = 36) String parentId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 60) String code,
        @NotNull InventoryNode.Kind kind,
        @NotNull InventoryNode.Status status,
        @Min(1) @Max(100000) int quantity,
        @NotNull @DecimalMin("0.00") @DecimalMax("999999999999.99") @Digits(integer = 12, fraction = 2) BigDecimal unitValue,
        @NotNull @Size(max = 120) String responsible,
        @NotNull @Size(max = 2000) String description,
        @NotNull InventoryNode.Visibility visibility) {}
