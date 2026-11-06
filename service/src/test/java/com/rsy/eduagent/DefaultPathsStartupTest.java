package com.rsy.eduagent;

import com.rsy.eduagent.config.EvalSettings;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** Use application.yml defaults for both paths; only infrastructure is replaced by local test settings. */
@SpringBootTest(properties = {
        "DIFY_API_KEY=test-placeholder-not-a-real-key",
        "eduagent.dify.base-url=http://127.0.0.1:1/v1",
        "spring.datasource.url=jdbc:h2:mem:defaultpaths;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.placeholders.tableOptions=",
        "logging.level.root=ERROR"
})
class DefaultPathsStartupTest {
    @Autowired EvalSettings settings;

    @Test void contextStartsWithDefaultRelativeEvaluationPathsAndFakeKey() {
        assertThat(settings.questionsPath()).isEqualTo("../eval/questions.json");
        assertThat(settings.reportsDir()).isEqualTo("../eval/reports");
        assertThat(settings.resolvedQuestionsPath()).isAbsolute()
                .isEqualTo(Paths.get("../eval/questions.json").toAbsolutePath().normalize());
        assertThat(settings.resolvedReportsDir()).isAbsolute()
                .isEqualTo(Paths.get("../eval/reports").toAbsolutePath().normalize());
    }
}
