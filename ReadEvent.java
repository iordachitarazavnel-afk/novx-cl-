public class ReadEvent {
    public static void main(String[] args) throws Exception {
        Class<?> k = Class.forName("net.minecraft.client.input.CharacterEvent");
        for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
            System.out.println(m.getName() + " -> " + m.getReturnType().getName());
        }
    }
}
