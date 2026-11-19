package org.uvo.uvostore.entity.payment.enums;

/** IMP-07. El tipo de cuenta de la transferencia, con el nombre que se le muestra al comprador. */
public enum BankAccountType {
    CORRIENTE("Cuenta corriente"),
    VISTA("Cuenta vista"),
    AHORRO("Cuenta de ahorro"),
    RUT("CuentaRUT");

    private final String label;

    BankAccountType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
