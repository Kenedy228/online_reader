package com.example.online_reader.book.progress;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RangeProofTest {
    @Test void bindsValidatedUnicodeRangeToOwnerBookSourceAndRepresentation() {
        var proofs=new RangeProof();var position=new ReadingPosition("s2",103,"a".repeat(64),1);
        String proof=proofs.issue(7,9L,position,100,120);
        assertThat(proofs.valid(7,9L,position,proof)).isTrue();
        assertThat(proofs.valid(8,9L,position,proof)).isFalse();
        assertThat(proofs.valid(7,10L,position,proof)).isFalse();
        assertThat(proofs.valid(7,9L,new ReadingPosition("s2",120,"a".repeat(64),1),proof)).isFalse();
        assertThat(proofs.valid(7,9L,new ReadingPosition("s1",103,"a".repeat(64),1),proof)).isFalse();
        assertThat(proofs.valid(7,9L,new ReadingPosition("s2",103,"b".repeat(64),1),proof)).isFalse();
        assertThat(new RangeProof().valid(7,9L,position,proof)).isFalse();
    }
    @Test void supportsEmptyChapterAndRefusesForgedOrOversizedRanges() {
        var proofs=new RangeProof();var position=new ReadingPosition("s0",0,"b".repeat(64),1);
        String proof=proofs.issue(1,2L,position,0,0);
        assertThat(proofs.valid(1,2L,position,proof)).isTrue();
        assertThat(proofs.valid(1,2L,position,"0.16385."+proof.substring(proof.lastIndexOf('.')+1))).isFalse();
        assertThat(proofs.valid(1,2L,position,"garbage")).isFalse();
    }
}
