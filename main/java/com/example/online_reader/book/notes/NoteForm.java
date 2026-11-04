package com.example.online_reader.book.notes;

import com.example.online_reader.book.progress.ReadingPosition;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public class NoteForm {
    @NotBlank(message="Напишите заметку") @CodePointLength(max=2000,message="Не больше 2000 Unicode-символов")
    private String text="";
    private String chapter,fingerprint,proof;
    private Integer offset,version;
    public String getText(){return text;}public void setText(String value){text=NoteText.lineEndings(value);}
    public String getChapter(){return chapter;}public void setChapter(String value){chapter=value;}
    public Integer getOffset(){return offset;}public void setOffset(Integer value){offset=value;}
    public String getFingerprint(){return fingerprint;}public void setFingerprint(String value){fingerprint=value;}
    public Integer getVersion(){return version;}public void setVersion(Integer value){version=value;}
    public String getProof(){return proof;}public void setProof(String value){proof=value;}
    public ReadingPosition position(){return new ReadingPosition(chapter,offset,fingerprint,version);}
    public void position(ReadingPosition value,String proof){chapter=value.chapter();offset=value.offset();fingerprint=value.fingerprint();version=value.version();this.proof=proof;}
}
