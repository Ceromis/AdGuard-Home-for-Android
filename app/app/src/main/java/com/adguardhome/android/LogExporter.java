package com.adguardhome.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LogExporter {
    private LogExporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: LogExporter <log-dir> <zip-file>");
        File source = new File(args[0]);
        File destination = new File(args[1]);
        if (!source.isDirectory()) throw new IOException("log directory is unavailable: " + source);
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("cannot create export directory: " + parent);
        }
        File temporary = new File(destination.getPath() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
            addDirectory(zip, source, source.getAbsolutePath().length() + 1);
        }
        if (destination.exists() && !destination.delete()) throw new IOException("cannot replace old log archive");
        if (!temporary.renameTo(destination)) throw new IOException("cannot publish log archive");
        System.out.println(destination.getAbsolutePath());
    }

    private static void addDirectory(ZipOutputStream zip, File directory, int prefixLength) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[16 * 1024];
        for (File file : files) {
            if (file.isDirectory()) {
                addDirectory(zip, file, prefixLength);
                continue;
            }
            String name = file.getAbsolutePath().substring(prefixLength).replace(File.separatorChar, '/');
            zip.putNextEntry(new ZipEntry(name));
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                int count;
                while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }
}
