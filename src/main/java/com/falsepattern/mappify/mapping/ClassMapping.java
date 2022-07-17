package com.falsepattern.mappify.mapping;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
public class ClassMapping {
    public final String originalName;
    public final String targetName;
    @Getter
    private String mappedSuperClass;
    private final String superClass;
    private final String[] interfaces;
    private boolean inheritanceProcessed = false;
    public final List<MemberMapping> fields = new ArrayList<>();
    public final List<MemberMapping> methods = new ArrayList<>();

    public static ClassMapping autoMap(JavaClass clazz) {
        String name = clazz.getClassName();
        String pkg = "";
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            pkg = name.substring(0, i + 1);
            name = name.substring(i + 1);
        }
        String mappedName;
        if (name.matches("Class_\\d+")) {
            mappedName = "Class_X" + name.substring(6);
        } else {
            mappedName = "Class_" + Util.uniqueID();
        }
        val mapping = new ClassMapping(pkg + name, pkg + mappedName, clazz.getSuperclassName(), clazz.getInterfaceNames());
        for (Field field : clazz.getFields()) {
            MemberMapping.autoMap(mapping, field);
        }
        for (Method method: clazz.getMethods()) {
            if (method.getName().equals("<clinit>")) {
                continue;
            }
            MemberMapping.autoMap(mapping, method);
        }
        mapping.fields.sort(Comparator.comparing((field) -> field.originalName));
        mapping.methods.sort(Comparator.comparing((method) -> method.originalName));
        return mapping;
    }

    public static ClassMapping deserialize(String line) {
        val parts = line.split(" ");
        return new ClassMapping(parts[1], parts[2], "", new String[0]);
    }

    public void addMember(MemberMapping member) {
        if (member.method) {
            methods.add(member);
        } else {
            fields.add(member);
        }
    }

    public void processInheritance(List<ClassMapping> mappings) {
        if (inheritanceProcessed) return;
        inheritanceProcessed = true;
        val interfaces = new ArrayList<>(Arrays.asList(this.interfaces));
        for (val otherClass: mappings) {
            if (otherClass.originalName.equals(superClass)) {
                otherClass.processInheritance(mappings);
                mappedSuperClass = otherClass.targetName;
                inheritMethods(otherClass);
            }
            if (interfaces.contains(otherClass.originalName)) {
                otherClass.processInheritance(mappings);
                inheritMethods(otherClass);
                interfaces.remove(otherClass.originalName);
            }
        }
        if (mappedSuperClass == null) {
            mappedSuperClass = superClass;
        }
    }

    private void inheritMethods(ClassMapping clazz) {
        for (val method: methods) {
            if (method.originalName.equals("<clinit>") || method.originalName.equals("<init>"))
                continue;
            if (clazz.methods.contains(method)) {
                val otherMethod = clazz.methods.get(clazz.methods.indexOf(method));
                method.inherit(otherMethod);
                System.out.print("Inherited method:\n" + method + otherMethod.toString());
            }
        }
    }

    public String classMap() {
        return "CL: " + originalName + " " + targetName + "\n";
    }

    public String fieldMap() {
        val result = new StringBuilder();
        fields.forEach((field) -> result.append(field.toString()));
        return result.toString();
    }

    public String methodMap() {
        val result = new StringBuilder();
        methods.forEach((method) -> result.append(method.toString()));
        return result.toString();
    }

    @Override
    public String toString() {
        return classMap() + fieldMap() + methodMap();
    }
}
