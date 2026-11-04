package com.example.online_reader.book.files;

import java.net.URI;
import java.nio.file.Path;

/** Shared virtual archive path policy for ingestion and reading; never filesystem extraction. */
public final class EpubPaths {
    private EpubPaths() { }
    public static String directory(String path){int slash=path.lastIndexOf('/');return slash<0?"":path.substring(0,slash+1);}
    public static boolean safeName(String path) {
        if(path==null || path.isEmpty() || path.length()>1024 || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("%") || path.codePoints().anyMatch(Character::isISOControl))return false;
        for(String part:path.split("/",-1))if(part.equals("..") || part.equals("."))return false;
        return true;
    }
    public static String reference(String base,String value) {
        if(value==null || value.isBlank())throw BookFileException.invalid();
        var uri=URI.create(value);
        if(uri.isAbsolute() || uri.getRawAuthority()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPath().startsWith("/") || uri.getPath().contains("\\"))throw BookFileException.invalid();
        String result=Path.of(base).resolve(uri.getPath()).normalize().toString().replace('\\','/');
        if(!safeName(result))throw BookFileException.invalid();return result;
    }
}
