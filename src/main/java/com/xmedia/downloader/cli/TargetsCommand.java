package com.xmedia.downloader.cli;

import com.xmedia.downloader.config.AppConfig;
import com.xmedia.downloader.config.ConfigException;
import com.xmedia.downloader.model.Target;
import com.xmedia.downloader.model.TargetStatus;
import com.xmedia.downloader.storage.TargetRepository;
import com.xmedia.downloader.target.TargetListService;
import com.xmedia.downloader.task.TargetListTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code targets} 子命令：ID 列表管理（增加 / 删除 / 校验 / 按顺序下载）。
 *
 * <pre>
 *   targets add Susu0526m 某人
 *   targets list
 *   targets check          # 校验全部；无效的会标出来并提示删除
 *   targets remove 3       # 按列表序号删除
 *   targets remove-invalid # 一键删除全部无效 ID
 *   targets move 3 up
 *   targets run            # 按列表顺序逐个下载
 * </pre>
 */
@Command(name = "targets", mixinStandardHelpOptions = true,
        description = "ID 列表：add / list / check / remove / remove-invalid / move / run（按顺序批量下载）",
        subcommands = {TargetsCommand.Add.class, TargetsCommand.ListCmd.class, TargetsCommand.Check.class,
                TargetsCommand.Remove.class, TargetsCommand.RemoveInvalid.class, TargetsCommand.Move.class,
                TargetsCommand.Run.class, TargetsCommand.Toggle.class})
public final class TargetsCommand implements Callable<Integer> {

    @ParentCommand
    MainCommand parent;

    @Override
    public Integer call() {
        System.out.println("请用子命令：add / list / check / remove / remove-invalid / move / toggle / run");
        System.out.println("示例： targets add Susu0526m；targets run");
        return ExitCodes.OK;
    }

    private AppConfig config() throws ConfigException {
        return parent.loadConfig();
    }

    // ------------------------------------------------------------ add
    @Command(name = "add", mixinStandardHelpOptions = true, description = "把 ID 加入列表（可多个；支持 @名字或 x.com/名字 形式）")
    static final class Add implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @picocli.CommandLine.Parameters(arity = "1..*", paramLabel = "ID",
                description = "用户名（可多个）；最后一个参数若以 # 开头则视为备注")
        List<String> names = new ArrayList<>();

        @Option(names = {"-n", "--note"}, description = "备注")
        String note = "";

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<String> targets = new ArrayList<>(names);
                String effectiveNote = note;
                if (targets.size() > 1 && targets.get(targets.size() - 1).startsWith("#")) {
                    effectiveNote = targets.remove(targets.size() - 1).substring(1);
                }
                int added = 0;
                for (String raw : targets) {
                    try {
                        TargetRepository.AddOutcome outcome = boot.targets().add(raw, effectiveNote);
                        if (outcome.created()) {
                            added++;
                            System.out.println("已加入：" + outcome.target().label());
                        } else {
                            System.out.println("已在列表中（跳过）：" + outcome.target().label());
                        }
                    } catch (IllegalArgumentException e) {
                        System.out.println("[跳过] " + e.getMessage());
                    }
                }
                System.out.println("新增 " + added + " 个，列表共 " + boot.targets().count() + " 个");
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ list
    @Command(name = "list", mixinStandardHelpOptions = true, description = "列出 ID 列表（含校验状态）")
    static final class ListCmd implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<Target> all = boot.targets().list();
                if (all.isEmpty()) {
                    System.out.println("（列表为空，用 targets add <ID> 增加）");
                    return ExitCodes.OK;
                }
                System.out.printf("%-4s %-18s %-10s %-8s %-8s %-6s %s%n",
                        "序号", "ID", "状态", "推文", "媒体", "启用", "备注/错误");
                int i = 1;
                for (Target t : all) {
                    System.out.printf("%-4d %-18s %-10s %-8d %-8d %-6s %s%n", i++, t.screenName(),
                            statusText(t.status()), t.tweetCount(), t.mediaCount(), t.enabled() ? "是" : "否",
                            t.status() == TargetStatus.INVALID ? t.lastError() : t.note());
                }
                long invalid = boot.targets().invalidCount();
                if (invalid > 0) {
                    System.out.println("提醒：有 " + invalid + " 个无效 ID，建议用 targets remove-invalid 删除。");
                }
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ check
    @Command(name = "check", mixinStandardHelpOptions = true, description = "校验列表里的 ID 是否有效（无效的标红提示删除）")
    static final class Check implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @Option(names = "--all", description = "连已校验有效的也重新校验（默认只校验未校验/已判无效的）")
        boolean all;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                System.out.println("开始校验 " + boot.targets().count() + " 个 ID …");
                TargetListService.CheckReport report = boot.targetListService().checkAll(!all);
                for (Target t : boot.targets().list()) {
                    System.out.printf("  %-18s %-10s %s%n", t.screenName(), statusText(t.status()),
                            t.status() == TargetStatus.INVALID ? t.lastError() : t.label());
                }
                System.out.printf("校验完成：有效 %d / 无效 %d（共 %d）%n",
                        report.validCount(), report.invalidCount(), report.checkedCount());
                if (report.invalidCount() > 0) {
                    System.out.println("提醒：无效 ID → " + report.invalidNames()
                            + "；可用 targets remove-invalid 删除。");
                }
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ remove
    @Command(name = "remove", mixinStandardHelpOptions = true, description = "按列表序号删除（序号见 targets list）")
    static final class Remove implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @picocli.CommandLine.Parameters(arity = "1..*", paramLabel = "序号", description = "列表序号，可多个")
        List<Integer> indexes = new ArrayList<>();

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<Target> all = boot.targets().list();
                int removed = 0;
                for (int idx : indexes) {
                    if (idx < 1 || idx > all.size()) {
                        System.out.println("[跳过] 序号越界：" + idx);
                        continue;
                    }
                    Target t = all.get(idx - 1);
                    removed += boot.targets().remove(t.id());
                    System.out.println("已删除：" + t.label());
                }
                System.out.println("删除 " + removed + " 个，剩余 " + boot.targets().count() + " 个");
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ remove-invalid
    @Command(name = "remove-invalid", mixinStandardHelpOptions = true, description = "一键删除全部无效 ID")
    static final class RemoveInvalid implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<Target> invalid = boot.targets().invalid();
                if (invalid.isEmpty()) {
                    System.out.println("没有无效 ID。");
                    return ExitCodes.OK;
                }
                invalid.forEach(t -> System.out.println("  删除无效：" + t.screenName() + " ← " + t.lastError()));
                int removed = boot.targets().removeInvalid();
                System.out.println("已删除 " + removed + " 个无效 ID，剩余 " + boot.targets().count() + " 个");
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ move
    @Command(name = "move", mixinStandardHelpOptions = true, description = "调整顺序： move <序号> up|down")
    static final class Move implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @picocli.CommandLine.Parameters(index = "0", paramLabel = "序号", description = "列表序号")
        int index;

        @picocli.CommandLine.Parameters(index = "1", paramLabel = "方向", description = "up | down")
        String direction;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<Target> all = boot.targets().list();
                if (index < 1 || index > all.size()) {
                    System.out.println("[失败] 序号越界：" + index);
                    return ExitCodes.GENERAL;
                }
                boolean up = direction.equalsIgnoreCase("up") || direction.equals("上");
                boolean moved = boot.targets().move(all.get(index - 1).id(), up ? -1 : 1);
                System.out.println(moved ? "已调整顺序（新的顺序见 targets list）" : "已在端点上，未移动");
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ toggle
    @Command(name = "toggle", mixinStandardHelpOptions = true, description = "启用/停用某条（停用=保留但不参与批量下载）")
    static final class Toggle implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @picocli.CommandLine.Parameters(paramLabel = "序号", description = "列表序号")
        int index;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                List<Target> all = boot.targets().list();
                if (index < 1 || index > all.size()) {
                    System.out.println("[失败] 序号越界：" + index);
                    return ExitCodes.GENERAL;
                }
                Target t = all.get(index - 1);
                boot.targets().setEnabled(t.id(), !t.enabled());
                System.out.println((t.enabled() ? "已停用：" : "已启用：") + t.label());
                return ExitCodes.OK;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    // ------------------------------------------------------------ run
    @Command(name = "run", mixinStandardHelpOptions = true, description = "按列表顺序逐个下载（跳过无效/停用）")
    static final class Run implements Callable<Integer> {

        @ParentCommand
        TargetsCommand root;

        @Option(names = "--limit", defaultValue = "0", description = "每个用户最多处理多少条推文（0 = 不限）")
        int limit;

        @Option(names = "--fresh", description = "忽略增量水位线")
        boolean fresh;

        @Override
        public Integer call() {
            try (AppBootstrap boot = AppBootstrap.create(root.config(), root.parent.operationsOverride())) {
                boolean dryRun = root.parent.dryRun;
                TargetListTask.Summary summary = boot.targetListTask().run(
                        TargetListTask.Options.of(limit, dryRun, fresh),
                        target -> System.out.println("→ 正在处理：" + target.label()));
                System.out.println(dryRun ? "（dry-run：只列清单，未下载）" : summary.describe());
                if (!summary.failedTargets().isEmpty()) {
                    System.out.println("失败目标：" + summary.failedTargets());
                }
                // 有失败目标 → 非 0 退出（1 = 部分/全部失败），便于脚本判断
                return summary.failed() == 0 ? ExitCodes.OK : ExitCodes.GENERAL;
            } catch (ConfigException e) {
                System.err.println("[配置错误] " + e.getMessage());
                return ExitCodes.CONFIG;
            }
        }
    }

    static String statusText(TargetStatus status) {
        return switch (status) {
            case VALID -> "有效";
            case INVALID -> "无效";
            default -> "未校验";
        };
    }
}
