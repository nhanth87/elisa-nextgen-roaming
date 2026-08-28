package et.elisa.fabric;

/**
 * Fabric node role. Each Elisa service runs exactly one role: STP (SS7 edge),
 * IWF (MAP↔Diameter translation), DRA (Diameter routing/relay). Together they
 * form ONE IR.88 DEA surface (design §2).
 */
public enum FabricRole {
    STP,
    IWF,
    DRA;

    @Override
    public String toString() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}