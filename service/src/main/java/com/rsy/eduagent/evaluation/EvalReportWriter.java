package com.rsy.eduagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsy.eduagent.chat.ChatModels;
import com.rsy.eduagent.config.EvalSettings;
import com.rsy.eduagent.persistence.EvalResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EvalReportWriter {
    private final EvalSettings settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    public EvalReportWriter(EvalSettings settings, ObjectMapper mapper, Clock clock) {
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Path write(long runId, List<EvalResult> rows, int routeCorrect, int refusalCorrect,
                      int refusalTotal, double average, long maximum, int failed) throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(clock.instant());
        Path directory = settings.resolvedReportsDir();
        Files.createDirectories(directory);
        Path report = directory.resolve("eval-" + timestamp + ".md");
        StringBuilder text = new StringBuilder("# eduagent 批量评测\n\n");
        text.append("Run ID：").append(runId).append("；报告时间：").append(timestamp)
                .append("（Asia/Shanghai）。\n\n")
                .append("| 指标 | 结果 |\n| --- | --- |\n")
                .append("| 路由准确率 | ").append(rate(routeCorrect, rows.size())).append(" |\n")
                .append("| 拒答准确率（仅 out_of_scope） | ").append(rate(refusalCorrect, refusalTotal)).append(" |\n")
                .append("| 平均延迟 | ").append(String.format(Locale.ROOT, "%.2f ms", average)).append(" |\n")
                .append("| 最大延迟 | ").append(maximum).append(" ms |\n")
                .append("| Dify 调用失败数 | ").append(failed).append(" |\n\n")
                .append("延迟为 Dify 调用耗时，含失败请求，不含数据库保存和题间等待。路由为空按不正确计。")
                .append("拒答只做字面短语检查，不能代表答案的科学正确性；qa / generate 一律 MANUAL。\n\n")
                .append("| id | type | 预期路由 | 实际路由 | auto_check | 延迟 ms | 检索命中（拒答时不展示给用户） | 答案前 120 字 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (EvalResult row : rows) {
            JsonNode resources = mapper.readTree(row.citationsJson);
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode resource : resources) names.add(resource.path("document_name").asText(""));
            String documents = String.join("、", names);
            text.append("| ").append(cell(row.questionId)).append(" | ").append(cell(row.type))
                    .append(" | ").append(cell(row.expectedRoute)).append(" | ").append(cell(row.actualRoute))
                    .append(" | ").append(cell(row.autoCheck)).append(" | ").append(row.latencyMs)
                    .append(" | ").append(cell(documents)).append(" | ")
                    .append(cell(ChatModels.truncate(row.answer, 120))).append(" |\n");
        }
        // Never overwrite another run's report, even if its timestamp happens to collide.
        return Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String rate(int correct, int total) {
        return total == 0 ? "N/A（分母为 0）" : String.format(Locale.ROOT, "%d/%d（%.2f%%）", correct, total, correct * 100.0 / total);
    }
    private static String cell(String text) {
        if (text == null) return "—";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }
}
