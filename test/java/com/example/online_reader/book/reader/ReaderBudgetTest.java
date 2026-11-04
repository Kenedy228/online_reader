package com.example.online_reader.book.reader;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class ReaderBudgetTest {
    @Test void skipObeysByteBudgetAndInputStreamEofContract()throws Exception {
        try(var empty=new ReaderBudget().limit(new ByteArrayInputStream(new byte[0]),2)) {
            assertThat(empty.skip(-1)).isZero();assertThat(empty.skip(10)).isZero();
        }
        try(var input=new ReaderBudget().limit(new ByteArrayInputStream(new byte[3]),2)) {
            assertThatThrownBy(()->input.skip(3)).isInstanceOf(IOException.class);
        }
    }
}
