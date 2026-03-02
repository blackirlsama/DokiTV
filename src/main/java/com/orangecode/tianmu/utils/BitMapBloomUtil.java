package com.orangecode.tianmu.utils;

import cn.hutool.bloomfilter.BitMapBloomFilter;


public class BitMapBloomUtil {

    /**
     * 布隆过滤器
     */
    private final static BitMapBloomFilter bloomFilter = new BitMapBloomFilter(10);

    /**
     * 判断是否存在
     */
    public static boolean contains(String vid) {
        return bloomFilter.contains(vid);
    }

    /**
     * 添加元素
     */
    public static synchronized void add(String vid) {
        bloomFilter.add(vid);
    }


}