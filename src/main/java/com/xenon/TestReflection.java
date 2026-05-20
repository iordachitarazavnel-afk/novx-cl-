package com.xenon.mixin;

import net.minecraft.client.Keyboard;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class TestReflection {
    public static void main(String[] args) {
        System.out.println("Methods in Keyboard:");
        for (Method m : Keyboard.class.getDeclaredMethods()) {
            if (m.getName().equals("onChar")) {
                System.out.println(m.getName());
                for (Parameter p : m.getParameters()) {
                    System.out.println("  " + p.getType().getName());
                }
            }
        }
    }
}
