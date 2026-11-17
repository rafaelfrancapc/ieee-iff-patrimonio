package br.com.ieeeiffcampos.patrimonio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record InventoryNode(String id, String parentId, String name, String code, Kind kind,
                            Status status, int quantity, BigDecimal unitValue,
                            String responsible, String description, OffsetDateTime updatedAt, Visibility visibility) {
    public enum Kind { SPACE, FURNITURE, CONTAINER, ITEM }
    public enum Status { AVAILABLE, IN_USE, MAINTENANCE }
    public enum Visibility { PUBLIC, CONFIDENTIAL }
}
