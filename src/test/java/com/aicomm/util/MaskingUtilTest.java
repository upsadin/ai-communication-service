package com.aicomm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @Test
    void maskPhone_hidesMiddle() {
        assertThat(MaskingUtil.maskPhone("+79817110577")).isEqualTo("+798***0577");
    }

    @Test
    void maskPhone_handlesNull() {
        assertThat(MaskingUtil.maskPhone(null)).isEqualTo("***");
    }

    @Test
    void maskPhone_handlesShort() {
        assertThat(MaskingUtil.maskPhone("123")).isEqualTo("***");
    }

    @Test
    void maskContactId_hidesMiddle() {
        assertThat(MaskingUtil.maskContactId("491865728")).isEqualTo("4918***728");
    }

    @Test
    void maskContactId_handlesNull() {
        assertThat(MaskingUtil.maskContactId(null)).isEqualTo("***");
    }

    @Test
    void maskName_singleName() {
        assertThat(MaskingUtil.maskName("Viktor")).isEqualTo("V***");
    }

    @Test
    void maskName_fullName() {
        assertThat(MaskingUtil.maskName("Viktor Vikulitko")).isEqualTo("V. V***");
    }

    @Test
    void maskName_handlesNull() {
        assertThat(MaskingUtil.maskName(null)).isEqualTo("***");
    }

    @Test
    void maskChatId_hidesStart() {
        assertThat(MaskingUtil.maskChatId(491865728L)).isEqualTo("***5728");
    }
}
