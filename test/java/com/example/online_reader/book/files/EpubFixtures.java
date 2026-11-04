package com.example.online_reader.book.files;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class EpubFixtures {
    private EpubFixtures() { }
    public static Map<String,byte[]> content() {
        var files=new LinkedHashMap<String,byte[]>();
        files.put("mimetype",bytes("application/epub+zip"));
        files.put("META-INF/container.xml",bytes("<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>"));
        files.put("OPS/book.opf",bytes("<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>synthetic</dc:identifier><dc:title>Книга</dc:title><dc:language>ru</dc:language></metadata><manifest><item id='text' href='text.xhtml' media-type='application/xhtml+xml'/><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/></manifest><spine><itemref idref='text'/></spine></package>"));
        files.put("OPS/text.xhtml",bytes("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Книга</title></head><body><p>Синтетическая книга 🙂</p></body></html>"));
        files.put("OPS/nav.xhtml",bytes("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Оглавление</title></head><body><nav epub:type='toc'><ol><li><a href='text.xhtml'>Начало</a></li></ol></nav></body></html>"));
        return files;
    }
    public static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    public static Path write(Path target,Map<String,byte[]> files)throws IOException {
        try(var zip=new ZipOutputStream(Files.newOutputStream(target))) {
            for(var file:files.entrySet()) {
                var entry=new ZipEntry(file.getKey());
                if(file.getKey().equals("mimetype")){var crc=new CRC32();crc.update(file.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(file.getValue().length);entry.setCrc(crc.getValue());}
                zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();
            }
        }
        return target;
    }
}
