package com.rsy.eduagent;

import com.rsy.eduagent.chat.ChatModels.Reference;
import com.rsy.eduagent.chat.CitationPolicy;
import com.rsy.eduagent.config.CitationSettings;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class CitationPolicyTest {
    private final List<Reference> references = List.of(new Reference("physics.md", 1, 0.9, "原文"));
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CitationSettings.class)
    static class PropertiesOnly {}

    @Test void configurablePrefixesBindAndOnlyMatchAtStart() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("eduagent.citations.refusal-prefixes=资料未收录,无法依据讲义回答")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CitationPolicy policy = new CitationPolicy(context.getBean(CitationSettings.class));
                    assertThat(policy.visibleReferences("资料未收录该主题", references)).isEmpty();
                    assertThat(policy.visibleReferences("无法依据讲义回答该问题", references)).isEmpty();
                    assertThat(policy.visibleReferences("这是引用：资料未收录", references)).isEqualTo(references);
                    assertThat(policy.visibleReferences("知识库中没有找到相关内容", references)).isEqualTo(references);
                });
    }

    @Test void blankPrefixCannotAccidentallyHideAllCitations() {
        assertThatThrownBy(() -> new CitationSettings(List.of("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusalWithLeadingWhitespaceHidesCitations() {
        CitationPolicy policy = new CitationPolicy(new CitationSettings(List.of("知识库中没有找到相关内容")));
        String answer = "\n  知识库中没有找到相关内容……\n ";
        assertThat(policy.visibleReferences(answer, references)).isEmpty();
        assertThat(policy.visibleReferences("\n  正常回答：知识库中没有找到相关内容……", references))
                .isEqualTo(references);
    }
}
