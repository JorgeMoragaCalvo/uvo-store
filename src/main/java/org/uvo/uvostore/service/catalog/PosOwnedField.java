package org.uvo.uvostore.service.catalog;

/**
 * POS-2. Los campos de un producto que pueden pertenecer a UvoPOS. Cuáles pertenecen de verdad lo deciden los
 * flags de su {@code ProductSyncMapping} ({@link PosCatalogOwnership#lockedFields}).
 *
 * <p>{@code formField} es el nombre del campo en {@code ProductRequest} y en el formulario del panel: es lo que
 * viaja en {@code PosOwnershipDto} para que el formulario sepa qué bloquear.
 */
public enum PosOwnedField {

    NAME("name", "el nombre", false),
    DESCRIPTION("description", "la descripción", false),
    SKU("sku", "el SKU", true),
    PRICE("price", "el precio", true),
    STOCK("stock", "el stock", true),
    MANAGE_STOCK("manageStock", "la gestión de stock", true);

    private final String formField;
    private final String label;
    private final boolean simpleOnly;

    PosOwnedField(String formField, String label, boolean simpleOnly) {
        this.formField = formField;
        this.label = label;
        this.simpleOnly = simpleOnly;
    }

    public String formField() {
        return formField;
    }

    public String label() {
        return label;
    }

    /**
     * Si {@code ProductServiceImpl.updateProduct} solo lo escribe en un producto simple. En uno variable esos
     * cuatro salen de sus variantes, así que el panel no los toca y no hay nada que comparar.
     */
    boolean simpleOnly() {
        return simpleOnly;
    }
}
