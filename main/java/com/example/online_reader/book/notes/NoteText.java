package com.example.online_reader.book.notes;

/** Notes are bounded Unicode plain text, never HTML/Markdown. */
public final class NoteText {
    private NoteText() { }
    public static String lineEndings(String input){return input==null?null:input.replace("\r\n","\n").replace('\r','\n');}
    public static String normalize(String input) {
        if(input==null)throw new IllegalArgumentException("Invalid note text");
        for(int i=0;i<input.length();) {
            char c=input.charAt(i);int cp=input.codePointAt(i);
            if(Character.isSurrogate(c) && Character.charCount(cp)==1 || Character.isISOControl(cp) && cp!='\t' && cp!='\r' && cp!='\n')throw new IllegalArgumentException("Invalid note text");
            i+=Character.charCount(cp);
        }
        String text=lineEndings(input).strip();
        if(text.isBlank() || text.codePointCount(0,text.length())>2000)throw new IllegalArgumentException("Invalid note text");
        return text;
    }
}
