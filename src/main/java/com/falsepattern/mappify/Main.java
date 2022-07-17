package com.falsepattern.mappify;

import com.falsepattern.mappify.mapping.ClassMapping;
import com.falsepattern.mappify.mapping.MemberMapping;
import com.falsepattern.mappify.mapping.Util;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import lombok.var;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantCP;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantUtf8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            helpAndExit();
        }
        switch (args[0]) {
            case "dump":
                dump(args);
                break;
            case "convert":
                convert(args);
                break;
            default:
                helpAndExit();
        }
    }

    @SneakyThrows
    private static void convert(String[] args) {
        if (args.length != 4) {
            helpAndExit();
        }
        @Cleanup val inputJar = new JarFile(args[1]);
        @Cleanup val outputJar = new JarOutputStream(Files.newOutputStream(Paths.get(args[2])), inputJar.getManifest());
        val mappings = Util.parseClasses(Files.newInputStream(Paths.get(args[3])), hash(args[1]));
        val memberReplacements = mappings.stream().flatMap((mapping) -> {
            val result = new ArrayList<MemberMapping>();
            result.addAll(mapping.fields);
            result.addAll(mapping.methods);
            return result.stream();
        }).collect(Collectors.toList());
        val entries = inputJar.entries();
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement();
            if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                continue;
            }
            if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                val bytes = inputJar.getInputStream(entry);
                val parser = new ClassParser(bytes, entry.getName());
                val clazz = parser.parse();
                val mappingOpt = mappings.stream().filter((mapping) -> mapping.originalName.equals(clazz.getClassName())).findFirst();
                if (mappingOpt.isPresent()) {
                    val mapping = mappingOpt.get();
                    clazz.setFileName(mapping.targetName.replace('.', '/') + ".class");
                    val pool = clazz.getConstantPool();
                    pool.setConstant(((ConstantClass)pool.getConstant(clazz.getClassNameIndex())).getNameIndex(),
                                     new ConstantUtf8(mapping.targetName.replace('.', '/')));
                    var l = pool.getLength();
                    {
                        val stringIndices = new HashMap<String, Integer>();
                        for (int i = 0; i < l; i++) {
                            val constant = pool.getConstant(i);
                            if (constant instanceof ConstantUtf8) {
                                val utf8 = (ConstantUtf8) constant;
                                stringIndices.put(utf8.getBytes(), i);
                            }
                        }
                        for (val stringEntry : new HashMap<>(stringIndices).entrySet()) {
                            var str = stringEntry.getKey();
                            stringIndices.remove(str);
                            for (val theClazz : mappings) {
                                if (str.equals(theClazz.originalName)) {
                                    str = theClazz.targetName;
                                } else if (str.equals(theClazz.originalName.replace('.', '/'))) {
                                    str = theClazz.targetName.replace('.', '/');
                                } else {
                                    str = str.replace("L" + theClazz.originalName.replace('.', '/') + ";",
                                                      "L" + theClazz.targetName.replace('.', '/') + ";");
                                }
                            }
                            pool.setConstant(stringEntry.getValue(), new ConstantUtf8(str));
                            stringIndices.put(str, stringEntry.getValue());
                        }
                    }
                    val poolManager = new PoolStringManager(pool);
                    for (int i = 0; i < l; i++) {
                        val constant = pool.getConstant(i);
                        if (constant instanceof ConstantCP) {
                            var cp = (ConstantCP) constant;
                            var nat = (ConstantNameAndType) pool.getConstant(cp.getNameAndTypeIndex());
                            var className = cp.getClass(pool);
                            var cpName = nat.getName(pool);
                            var cpSig = nat.getSignature(pool);
                            for (val member: memberReplacements) {
                                if ((member.parent.originalName.equals(className) ||
                                     member.parent.targetName.equals(className)) &&
                                    (member.signature.equals(cpSig) ||
                                     member.getMappedSignature().equals(cpSig)) &&
                                    (member.originalName.equals(cpName) ||
                                     member.getTargetName().equals(cpName))) {
                                    pool.setConstant(cp.getClassIndex(), new ConstantClass(poolManager.getUtf8Index(member.parent.targetName.replace('.', '/'))));
                                    cp.setNameAndTypeIndex(poolManager.getNAT(member.getTargetName(), member.getMappedSignature()));
                                }
                            }
                        }
                    }
                    for (val field: clazz.getFields()) {
                        for (val mapField: mapping.fields) {
                            if (mapField.originalName.equals(field.getName()) &&
                                (mapField.signature.equals(field.getSignature()) || mapField.getMappedSignature().equals(field.getSignature()))) {
                                field.setNameIndex(poolManager.getUtf8Index(mapField.getTargetName()));
                                field.setSignatureIndex(poolManager.getUtf8Index(mapField.getMappedSignature()));
                                field.isSynthetic(false);
                            }
                        }
                    }
                    for (val method: clazz.getMethods()) {
                        for (val mapMethod: mapping.methods) {
                            if (mapMethod.originalName.equals(method.getName()) &&
                                (mapMethod.signature.equals(method.getSignature()) || mapMethod.getMappedSignature().equals(method.getSignature()))) {
                                method.setNameIndex(poolManager.getUtf8Index(mapMethod.getTargetName()));
                                method.setSignatureIndex(poolManager.getUtf8Index(mapMethod.getMappedSignature()));
                                method.isSynthetic(false);
                            }
                        }
                    }
                    if (clazz.isEnum()) {
                        val fields = clazz.getFields();
                        for (int i = 0; i < fields.length; i++) {
                            val field = fields[i];
                            if (field.isPrivate() && field.isStatic() && field.getSignature().equals("[L" + mapping.targetName.replace('.', '/') + ";")) {
                                field.isSynthetic(true);
                                for (int j = i; j < fields.length - 1; j++) {
                                    val b = fields[j + 1];
                                    fields[j + 1] = fields[j];
                                    fields[j] = b;
                                }
                                break;
                            }
                        }
                        for (val method: clazz.getMethods()) {
                            if ((method.getName().equals("values") && method.getSignature().equals("()[L" + mapping.targetName.replace('.', '/') + ";")) ||
                                (method.getName().equals("valueOf") && method.getSignature().equals("(Ljava/lang/String;)L" + mapping.targetName.replace('.', '/') + ";")) ||
                                (method.getName().equals("<init>"))) {
                                method.isSynthetic(true);
                            }
                        }
                    }
                }
                if (clazz.getSuperclassName().contains("Enum")) {
                    System.out.println(clazz.getFileName());
                }
                outputJar.putNextEntry(new ZipEntry(clazz.getFileName()));
                clazz.dump(outputJar);
                outputJar.closeEntry();
            } else {
                outputJar.putNextEntry(new ZipEntry(entry));
                pipe(inputJar.getInputStream(entry), outputJar, entry.getSize());
                outputJar.closeEntry();
            }
        }
    }

    private static void pipe(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buf = new byte[4096];
        long remaining = bytes;
        while (remaining > 0) {
            int read = input.read(buf, 0, (int) Math.min(buf.length, remaining));
            output.write(buf, 0, read);
            remaining -= read;
        }
    }

    @SneakyThrows
    private static void dump(String[] args) {

        val classMappings = new ArrayList<ClassMapping>();
        @Cleanup val jar = new ZipFile(args[1]);
        val entries = jar.entries();
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement();
            if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                val bytes = jar.getInputStream(entry);
                val parser = new ClassParser(bytes, entry.getName());
                val clazz = parser.parse();
                classMappings.add(ClassMapping.autoMap(clazz));
            }
        }
        for (val clazz: classMappings) {
            clazz.processInheritance(classMappings);
        }
        @Cleanup val outF = new FileOutputStream(args[2]);
        @Cleanup val out = new PrintStream(outF);
        out.write(hash(args[1]).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        Util.serializeClasses(classMappings, out);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @SneakyThrows
    private static String digest(String algo, byte[] data) {
        return bytesToHex(MessageDigest.getInstance(algo).digest(data));
    }

    @SneakyThrows
    private static String hash(String file) {
        byte[] data = Files.readAllBytes(Paths.get(file));
        return "HASH: " + "MD5 " + digest("MD5", data) + ";SHA-256 " + digest("SHA-256", data) + ";SHA-512 " + digest("SHA-512", data);
    }

    public static void helpAndExit() {
        System.out.println("Usage:\n" +
                           "dump <jarfile> -- Dumps all of the classes of a jar file into an autogenerated mapfile.\n" +
                           "convert <source jar> <target jar> <mapfile>\n");
        System.exit(0);
    }
}
