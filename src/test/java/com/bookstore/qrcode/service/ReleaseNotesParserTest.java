package com.bookstore.qrcode.service;

import com.bookstore.qrcode.dto.RawCommit;
import com.bookstore.qrcode.dto.ReleaseItem;
import com.bookstore.qrcode.dto.ReleaseVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("更新记录解析")
class ReleaseNotesParserTest {

    private final ReleaseNotesParser parser = new ReleaseNotesParser();

    @Test
    @DisplayName("解析定稿 YAML：日期、标题、条目与可选的类型标签")
    void parsesCuratedVersions() {
        String yaml = """
            versions:
              - date: 2026-11-05
                title: 接待员日限可自助配置
                items:
                  - kind: 新增
                    text: 系统配置页新增「接待员日限」卡片
                  - 修复「应用到全部」报 500 的问题
            """;

        List<ReleaseVersion> versions = parser.parseVersions(yaml);

        assertThat(versions).hasSize(1);
        ReleaseVersion v = versions.get(0);
        assertThat(v.date()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(v.title()).isEqualTo("接待员日限可自助配置");
        assertThat(v.items()).containsExactly(
            new ReleaseItem("新增", "系统配置页新增「接待员日限」卡片"),
            new ReleaseItem(null, "修复「应用到全部」报 500 的问题"));
        assertThat(v.coversFrom()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(v.coversTo()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    @DisplayName("covers 用 ~ 指定覆盖区间，省略时只覆盖自身日期")
    void parsesCoversRange() {
        String yaml = """
            versions:
              - date: 2026-11-04
                covers: 2026-11-02 ~ 2026-11-04
                title: 部门准入白名单
                items:
                  - 新增按部门限制接待员进池
            """;

        ReleaseVersion v = parser.parseVersions(yaml).get(0);

        assertThat(v.coversFrom()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(v.coversTo()).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    @DisplayName("原始提交只保留 feat/fix，并去掉类型前缀")
    void parsesRawCommitsKeepingOnlyFeatAndFix() {
        String raw = String.join("\n",
            "2026-11-05\tfeat: 系统配置页新增接待员日限组件",
            "2026-11-05\tfix(alert): 告警页「全部状态」筛选丢失",
            "2026-11-05\tdocs: 补上线说明",
            "2026-11-04\ttest: 加一个测试",
            "2026-11-04\tchore: 清理依赖");

        List<RawCommit> commits = parser.parseCommits(raw);

        assertThat(commits).containsExactly(
            new RawCommit(LocalDate.of(2026, 11, 5), "系统配置页新增接待员日限组件"),
            new RawCommit(LocalDate.of(2026, 11, 5), "告警页「全部状态」筛选丢失"));
    }

    @Test
    @DisplayName("空行与格式不对的行直接跳过，不让整页挂掉")
    void skipsMalformedCommitLines() {
        String raw = "\nnot-a-date\tfeat: ???\n2026-11-05\tfeat: 有效条目\n\n";

        List<RawCommit> commits = parser.parseCommits(raw);

        assertThat(commits).containsExactly(
            new RawCommit(LocalDate.of(2026, 11, 5), "有效条目"));
    }

    @Test
    @DisplayName("空 YAML 返回空列表而不是抛异常")
    void emptyYamlYieldsEmptyList() {
        assertThat(parser.parseVersions("")).isEmpty();
        assertThat(parser.parseVersions("versions: []")).isEmpty();
    }

    @Test
    @DisplayName("YAML 写坏时返回空列表，不让整页 500")
    void malformedYamlYieldsEmptyList() {
        assertThat(parser.parseVersions("versions: [")).isEmpty();
        assertThat(parser.parseVersions("versions:\n\t- date: 2026-11-05")).isEmpty();
    }

    @Test
    @DisplayName("covers 只有前半段时不抛异常，退回只覆盖到自身日期")
    void trailingCoversTildeIsTolerated() {
        String yaml = """
            versions:
              - date: 2026-11-05
                covers: 2026-11-02 ~
                title: 标题
                items:
                  - 内容
            """;

        ReleaseVersion v = parser.parseVersions(yaml).get(0);

        assertThat(v.coversFrom()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(v.coversTo()).isEqualTo(LocalDate.of(2026, 11, 5));
    }
}
