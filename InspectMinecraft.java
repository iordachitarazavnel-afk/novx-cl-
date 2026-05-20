import java.lang.reflect.Field;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.font.TextRenderer;
import java.lang.reflect.Method;

public class InspectMinecraft {
    public static void main(String[] args) {
        System.out.println("--- Input Fields ---");
        for (Field f : Input.class.getDeclaredFields()) {
            System.out.println(f.getType().getName() + " " + f.getName());
        }
        System.out.println("--- KeyboardInput Fields ---");
        for (Field f : KeyboardInput.class.getDeclaredFields()) {
            System.out.println(f.getType().getName() + " " + f.getName());
        }
        System.out.println("--- TextRenderer Methods ---");
        for (Method m : TextRenderer.class.getDeclaredMethods()) {
            if (m.getName().equals("draw")) {
                System.out.print(m.getName() + "(");
                Class<?>[] pTypes = m.getParameterTypes();
                for (int i = 0; i < pTypes.length; i++) {
                    System.out.print(pTypes[i].getSimpleName() + (i < pTypes.length - 1 ? ", " : ""));
                }
                System.out.println(")");
            }
        }
    }
}
