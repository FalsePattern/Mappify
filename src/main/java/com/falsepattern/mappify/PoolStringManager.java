package com.falsepattern.mappify;

import lombok.val;
import lombok.var;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.ConstantUtf8;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PoolStringManager {
    private final ConstantPool pool;
    private final Map<String, Integer> utf8Map = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> natMap = new HashMap<>();
    public PoolStringManager(ConstantPool pool) {
        this.pool = pool;
        val l = pool.getLength();
        for (int i = 0; i < l; i++) {
            val constant = pool.getConstant(i);
            if (constant instanceof ConstantUtf8) {
                val utf8 = (ConstantUtf8) constant;
                utf8Map.put(utf8.getBytes(), i);
            } else if (constant instanceof ConstantNameAndType) {
                val nat = (ConstantNameAndType) constant;
                natMap.computeIfAbsent(nat.getNameIndex(), k -> new HashMap<>()).put(nat.getSignatureIndex(), i);
            }
        }
    }

    private int appendToPool(Constant constant) {
        val i = pool.getLength();
        pool.setConstantPool(Arrays.copyOf(pool.getConstantPool(), i + 1));
        pool.setConstant(i, constant);
        return i;
    }

    public int getUtf8Index(String str) {
        return utf8Map.computeIfAbsent(str, k -> appendToPool(new ConstantUtf8(str)));
    }

    public int getNAT(String name, String type) {
        val iName = getUtf8Index(name);
        val iType = getUtf8Index(type);
        return natMap.computeIfAbsent(iName, k -> new HashMap<>())
                     .computeIfAbsent(iType, k -> appendToPool(new ConstantNameAndType(iName, iType)));
    }
}
