public final class TrustedNativeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("PRELOAD_READY");
        System.out.flush();
        System.in.read();
        boolean loaded = false;
        try {
            loaded = Class.forName("com.sun.jna.Native").getField("POINTER_SIZE").getInt(null) == 8;
            System.out.println(loaded ? "LOADED" : "WRONG_ARCH");
        } catch (LinkageError failure) {
            System.out.println("STOP");
        }
        System.out.println("READY");
        System.out.flush();
        System.in.read();
        if (!loaded) System.exit(10);
    }
}
