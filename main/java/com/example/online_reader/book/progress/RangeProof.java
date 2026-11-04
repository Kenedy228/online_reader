package com.example.online_reader.book.progress;

import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Stateless evidence of an already validated reader range, never a substitute for owner checks. */
@Component
public final class RangeProof {
    private final byte[] key=new byte[32];
    public RangeProof(){new SecureRandom().nextBytes(key);}
    public String issue(Integer owner,Long book,ReadingPosition position,int from,int next) {
        return from+"."+next+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(owner,book,position,from,next));
    }
    public boolean valid(Integer owner,Long book,ReadingPosition position,String proof) {
        if(proof==null || proof.length()>100 || !proof.matches("[0-9]{1,8}\\.[0-9]{1,8}\\.[A-Za-z0-9_-]{43}"))return false;
        try {
            var parts=proof.split("\\.");int from=Integer.parseInt(parts[0]),next=Integer.parseInt(parts[1]);
            if(from<0 || next<from || next-from>16384 || next>10485760 || position.offset()<from || (next==from?position.offset()!=from:position.offset()>=next))return false;
            return MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[2]),mac(owner,book,position,from,next));
        }catch(IllegalArgumentException e){return false;}
    }
    private byte[] mac(Integer owner,Long book,ReadingPosition position,int from,int next) {
        String value=owner+"\n"+book+"\n"+position.fingerprint()+"\n"+position.version()+"\n"+position.chapter()+"\n"+from+"\n"+next;
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        }catch(GeneralSecurityException e){throw new IllegalStateException("Cannot sign reader range");}
    }
}
