package com.falsepattern.mappify.mapping;

import lombok.val;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

public class Util {
    private static final Set<Integer> usedNumbers = new HashSet<>();
    private static final Random rng = new Random(1001);
    public static String uniqueID() {
        val str = new StringBuilder();
        String num;
        synchronized (usedNumbers) {
            while (true) {
                int n = rng.nextInt(1000000);
                if (usedNumbers.contains(n)) {
                    continue;
                }
                usedNumbers.add(n);
                num = Integer.toString(n);
                break;
            }
        }
        int length = num.length();
        for (; length < 6; length++) {
            str.append('0');
        }
        str.append(num);
        return str.toString();
    }

    public static String mapSignature(List<ClassMapping> mappings, String signature) {
        val remapped = new StringBuilder();
        val buf = new StringBuilder();
        boolean reading = false;
        val chars = signature.toCharArray();
        for (char aChar : chars) {
            if (reading) {
                if (aChar == ';') {
                    remapped.append(Util.translateClass(mappings, buf.toString()))
                            .append(';');
                    buf.setLength(0);
                    reading = false;
                } else {
                    buf.append(aChar);
                }
            } else {
                if (aChar == 'L') {
                    reading = true;
                }
                remapped.append(aChar);
            }
        }
        return remapped.toString();
    }

    public static List<ClassMapping> parseClasses(InputStream input, String hash) {
        val scan = new Scanner(input);
        val classes = new ArrayList<ClassMapping>();
        boolean foundHash = false;
        while (scan.hasNextLine()) {
            val line = scan.nextLine();
            if (line.startsWith("CL: ")) {
                classes.add(ClassMapping.deserialize(line));
            } else if (line.startsWith("HASH: ")) {
                if (!hash.equals(line)) {
                    throw new IllegalArgumentException("Invalid mapping hash! Cannot use this mapping file for this specific jar due to fear of corruption!\n" +
                                                       "   File " + hash + "\n" +
                                                       "Mapping " + line + "\n");
                } else {
                    foundHash = true;
                }
            } else if (line.startsWith("MD: ") || line.startsWith("FD: ")) {
                MemberMapping.deserialize(line, classes);
            } else if (!line.startsWith(";")) {
                throw new IllegalArgumentException("Invalid line in mapfile: " + line);
            }
        }
        if (!foundHash) {
            System.err.println("Missing mapping hash! Things might not work as expected!");
        }
        return classes;
    }

    public static void serializeClasses(List<ClassMapping> classes, OutputStream out) throws IOException {
        classes.sort(Comparator.comparing((clazz) -> clazz.originalName));
        for (val clazz : classes) {
            for (val method : clazz.methods) {
                method.mapSignature(classes);
            }
            for (val field: clazz.fields) {
                field.mapSignature(classes);
            }
        }
        for (val clazz : classes) {
            out.write(clazz.classMap().getBytes(StandardCharsets.UTF_8));
        }
        for (val clazz : classes) {
            out.write(clazz.fieldMap().getBytes(StandardCharsets.UTF_8));
        }
        for (val clazz : classes) {
            out.write(clazz.methodMap().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String translateClass(List<ClassMapping> classes, String className) {
        val formatted = className.replace('/', '.');
        for (val clazz: classes) {
            if (clazz.originalName.equals(formatted)) {
                return clazz.targetName.replace('.', '/');
            }
        }
        return className;
    }
}
