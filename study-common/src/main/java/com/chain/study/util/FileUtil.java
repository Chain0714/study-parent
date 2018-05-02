package com.chain.study.util;

import java.io.File;

/**
 * 
 * 文件工具类
 *
 * @author 88314871
 */
public class FileUtil {
    
    /**
     * 
     * 创建目录
     *
     * @param filePath
     */
    public static void makeDir(String filePath) {
        File file = new File(filePath);
        
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    /**
     * 
     * 删除文件
     *
     * @param jarFile
     */
    public static void deleteFile(String jarFile) {
        File file = new File(jarFile);
        
        if (file.exists()) {
            file.delete();
        }
    }
}
