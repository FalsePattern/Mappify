package com.falsepattern.mappify.mapping;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.var;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.Method;

import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class MemberMapping {
    public final ClassMapping parent;
    public final String originalName;

    @Getter
    @NonNull
    String targetName;
    public final String signature;
    @Getter
    private String mappedSignature;

    public final boolean method;

    public static void autoMap(ClassMapping parent, Field field) {
        var name = field.getName();
        if (name.length() < 3) {
            char suffix = field.getSignature().charAt(0);
            if (suffix == '[') {
                suffix = 'a';
            }
            name = "field_" + Util.uniqueID() + "_" + Character.toLowerCase(suffix);
        } else if (name.matches("field_\\d+_\\w")) {
            name = "field_X" + name.substring(6);
        }
        val member = new MemberMapping(parent, field.getName(), name, field.getSignature(), false);
        parent.addMember(member);
    }

    public static void autoMap(ClassMapping parent, Method method) {
        var name = method.getName();
        if (name.length() < 3) {
            name = "method_" + Util.uniqueID();
        } else if (name.matches("method_\\d+")) {
            name = "method_X" + name.substring(7);
        }
        val member = new MemberMapping(parent, method.getName(), name, method.getSignature(), true);
        parent.addMember(member);
    }

    public static void deserialize(String str, List<ClassMapping> classes) {
        val parts = str.split(" ");
        int i = parts[1].lastIndexOf('.');
        val className = parts[1].substring(0, i);
        val clazzOpt = classes.stream().filter((candidate) -> candidate.originalName.equals(className)).findFirst();
        if (!clazzOpt.isPresent()) {
            throw new IllegalArgumentException("The mapping file should have the class definitions BEFORE any member definitions! Missing class: " + className);
        }
        val clazz = clazzOpt.get();
        val member = new MemberMapping(clazz,
                                       parts[1].substring(i + 1),
                                       parts[3].substring(parts[3].lastIndexOf('.') + 1),
                                       parts[2],
                                       parts[0].equals("MD:"));
        member.mappedSignature = parts[4];
        clazz.addMember(member);
    }

    public void mapSignature(List<ClassMapping> mappings) {
        mappedSignature = Util.mapSignature(mappings, signature);
    }

    @Override
    public String toString() {
        return (method ? "MD: " : "FD: ") + parent.originalName + "." + originalName + " " + signature + " " + parent.targetName + "." + targetName + " " + mappedSignature + "\n";
    }

    public void inherit(MemberMapping superMember) {
        if (!this.equals(superMember)) {
            throw new IllegalArgumentException("Cannot inherit non-equivalent member");
        }
        targetName = superMember.targetName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MemberMapping that = (MemberMapping) o;
        return method == that.method && Objects.equals(originalName, that.originalName) &&
               Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalName, signature, method);
    }
}
