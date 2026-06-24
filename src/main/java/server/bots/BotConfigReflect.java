package server.bots;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic get/set over the public fields of a live config object (e.g. {@link BotCombatManager}'s
 * {@code Config} or {@link BotManager}'s {@code Config}). SSOT for the {@code !botcfg} command and the
 * {@code /api/settings} web admin menu — neither owns its own copy of this reflection.
 *
 * <p>ponytail: numeric/boolean fields only; a String knob lands in the "bad value" path. Add String
 * handling in {@link #parse} only when a real String field needs editing.
 */
public final class BotConfigReflect {
    private BotConfigReflect() {}

    /** "FIELD = value" for every public field of {@code cfg}, sorted. */
    public static List<String> fieldLines(Object cfg) {
        List<String> out = new ArrayList<>();
        for (Field f : cfg.getClass().getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            try {
                out.add(f.getName() + " = " + f.get(cfg));
            } catch (IllegalAccessException ignored) {
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** "FIELD = value" for one field (case-insensitive), or null if unknown. */
    public static String fieldLine(Object cfg, String name) {
        for (Field f : cfg.getClass().getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && f.getName().equalsIgnoreCase(name)) {
                try {
                    return f.getName() + " = " + f.get(cfg);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Set a field by name (case-insensitive) on the live {@code cfg}. Returns a human-readable result;
     * success messages start with "OK".
     */
    public static String setField(Object cfg, String name, String rawValue) {
        for (Field f : cfg.getClass().getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers()) || !f.getName().equalsIgnoreCase(name)) {
                continue;
            }
            try {
                Object parsed = parse(f.getType(), rawValue.trim());
                f.set(cfg, parsed);
                return "OK: " + f.getName() + " = " + parsed;
            } catch (NumberFormatException e) {
                return "bad value '" + rawValue + "' for " + f.getName() + " (" + f.getType().getSimpleName() + ")";
            } catch (IllegalAccessException e) {
                return "cannot set " + f.getName();
            }
        }
        return "unknown field: " + name;
    }

    /** One public field's name, current value, and simple type — lets a UI render the right input. */
    public record FieldView(String name, String value, String type) {}

    /** All public fields of {@code cfg} as {name,value,type}, sorted by name. */
    public static List<FieldView> fields(Object cfg) {
        List<FieldView> out = new ArrayList<>();
        for (Field f : cfg.getClass().getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            try {
                out.add(new FieldView(f.getName(), String.valueOf(f.get(cfg)), f.getType().getSimpleName()));
            } catch (IllegalAccessException ignored) {
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    private static Object parse(Class<?> type, String v) {
        if (type == boolean.class || type == Boolean.class) {
            if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on")) {
                return Boolean.TRUE;
            }
            if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off")) {
                return Boolean.FALSE;
            }
            throw new NumberFormatException(v);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(v);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(v);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(v);
        }
        if (type == float.class || type == Float.class) {
            return Float.parseFloat(v);
        }
        throw new NumberFormatException(v);
    }
}
