package com.winlator.core;

import com.winlator.xenvironment.RootFS;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeLocaleManager {
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final String[] REQUIRED_CATEGORIES = {
            "LC_CTYPE",
            "LC_COLLATE",
            "LC_TIME",
            "LC_NUMERIC",
            "LC_MONETARY",
            "LC_MESSAGES/SYS_LC_MESSAGES"
    };

    private RuntimeLocaleManager() {
    }

    public static void ensureAvailable(RootFS rootFS, String locale) throws IOException {
        ensureAvailable(rootFS.getRootDir(), locale, RuntimeLocaleManager::runProcess);
    }

    static void ensureAvailable(File rootDir, String locale, CommandRunner runner)
            throws IOException {
        if (locale == null || locale.isEmpty() || "system".equals(locale)) return;
        validate(locale);

        File localeRoot = new File(rootDir, "usr/lib/locale");
        File localeDirectory = new File(
                localeRoot,
                directoryName(locale)
        );
        if (isComplete(localeDirectory)) {
            StartupLog.log("Reusing runtime locale " + locale + " from " + localeDirectory);
            return;
        }
        if (localeDirectory.exists() && !FileUtils.delete(localeDirectory)) {
            throw failure(
                    locale,
                    "prepare_output",
                    null,
                    null,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "Unable to clear an incomplete runtime locale directory."
            );
        }
        if ((!localeRoot.isDirectory() && !localeRoot.mkdirs()) ||
                !localeRoot.isDirectory() ||
                !localeRoot.canWrite()) {
            throw failure(
                    locale,
                    "prepare_output",
                    null,
                    null,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "The runtime locale output directory is not writable."
            );
        }
        File localedef = new File(rootDir, "usr/bin/localedef");
        File linker = new File(rootDir, "usr/lib/ld-linux-aarch64.so.1");
        String input = locale.substring(0, locale.indexOf('.'));
        requireReadableFile(rootDir, locale, localedef, "localedef");
        requireReadableFile(rootDir, locale, linker, "the glibc dynamic linker");
        requireReadableFile(
                rootDir,
                locale,
                new File(rootDir, "usr/share/i18n/locales/" + input),
                "the " + input + " locale source"
        );
        requireReadableFile(
                rootDir,
                locale,
                new File(rootDir, "usr/share/i18n/charmaps/UTF-8.gz"),
                "the UTF-8 charmap"
        );
        requireReadableFile(
                rootDir,
                locale,
                new File(rootDir, "usr/lib/gconv/gconv-modules"),
                "the gconv module configuration"
        );
        if ((!linker.canExecute() && !linker.setExecutable(true, true)) ||
                (!localedef.canExecute() && !localedef.setExecutable(true, true))) {
            throw failure(
                    locale,
                    "prepare_tools",
                    null,
                    null,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "The runtime locale tools are not executable."
            );
        }
        if (!localeDirectory.mkdirs() && !localeDirectory.isDirectory()) {
            throw failure(
                    locale,
                    "prepare_output",
                    null,
                    null,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "The runtime locale directory could not be created."
            );
        }

        List<String> command = buildCommand(rootDir, locale);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(
                "PATH",
                "/system/bin:/system/xbin"
        );
        environment.put(
                "I18NPATH",
                new File(rootDir, "usr/share/i18n").getPath()
        );
        environment.put(
                "LOCPATH",
                localeRoot.getPath()
        );
        environment.put(
                "GCONV_PATH",
                new File(rootDir, "usr/lib/gconv").getPath()
        );
        StartupLog.log(
                "Generating runtime locale " + locale
                        + " argv=" + command
                        + " environment=" + environment
                        + " expectedOutput=" + localeDirectory
        );

        try {
            ProcessResult result = runner.run(command, rootDir, environment);
            boolean complete = isComplete(localeDirectory);
            StartupLog.log(
                    "Runtime locale generator finished locale=" + locale
                            + " exitStatus=" + result.exitStatus
                            + " complete=" + complete
                            + " output=" + printableOutput(result)
            );
            if (result.exitStatus != 0 || !complete) {
                throw failure(
                        locale,
                        result.exitStatus != 0 ? "localedef" : "verify_output",
                        command,
                        environment,
                        localeDirectory,
                        result.exitStatus,
                        result.output,
                        result.outputTruncated,
                        result.exitStatus != 0
                                ? "localedef returned a non-zero exit status."
                                : "localedef did not create a complete per-locale directory."
                );
            }
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            FileUtils.delete(localeDirectory);
            GenerationException failure = failure(
                    locale,
                    "localedef",
                    command,
                    environment,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "Runtime locale generation was interrupted."
            );
            failure.initCause(error);
            throw failure;
        }
        catch (GenerationException error) {
            FileUtils.delete(localeDirectory);
            throw error;
        }
        catch (IOException error) {
            FileUtils.delete(localeDirectory);
            GenerationException failure = failure(
                    locale,
                    "process_start",
                    command,
                    environment,
                    localeDirectory,
                    null,
                    "",
                    false,
                    "The runtime locale generator could not be started or read: "
                            + error.getMessage()
            );
            failure.initCause(error);
            throw failure;
        }
    }

    private static void requireReadableFile(
            File rootDir,
            String locale,
            File file,
            String description
    ) throws GenerationException {
        if (file.isFile() && file.canRead()) return;
        throw failure(
                locale,
                "validate_inputs",
                null,
                null,
                new File(rootDir, "usr/lib/locale/" + directoryName(locale)),
                null,
                "",
                false,
                "The Winlator rootfs does not provide readable " + description + "."
        );
    }

    private static ProcessResult runProcess(
            List<String> command,
            File workingDirectory,
            Map<String, String> environment
    ) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        boolean truncated = false;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        if (output.length() > 0) output.append('\n');
                        int remaining = MAX_OUTPUT_CHARS - output.length();
                        output.append(line, 0, Math.min(line.length(), remaining));
                        if (line.length() > remaining) truncated = true;
                    }
                    else {
                        truncated = true;
                    }
                }
            }
            return new ProcessResult(process.waitFor(), output.toString(), truncated);
        }
        catch (IOException | InterruptedException error) {
            terminate(process);
            throw error;
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            }
            catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static boolean isComplete(File localeDirectory) {
        if (localeDirectory == null || !localeDirectory.isDirectory()) return false;
        for (String category : REQUIRED_CATEGORIES) {
            if (!new File(localeDirectory, category).isFile()) return false;
        }
        return true;
    }

    static String directoryName(String locale) {
        int separator = locale.indexOf('.');
        String language = separator >= 0 ? locale.substring(0, separator) : locale;
        return language + ".utf8";
    }

    static List<String> buildCommand(File rootDir, String locale) {
        validate(locale);
        String input = locale.substring(0, locale.indexOf('.'));
        return Arrays.asList(
                new File(rootDir, "usr/lib/ld-linux-aarch64.so.1").getPath(),
                "--library-path",
                new File(rootDir, "usr/lib").getPath(),
                new File(rootDir, "usr/bin/localedef").getPath(),
                "--no-archive",
                "-i",
                input,
                "-f",
                "UTF-8",
                new File(
                        rootDir,
                        "usr/lib/locale/" + directoryName(locale)
                ).getPath()
        );
    }

    private static void validate(String locale) {
        if (!locale.matches("^[a-z]{2}_[A-Z]{2}\\.UTF-8$")) {
            throw new IllegalArgumentException("Unsupported runtime locale: " + locale);
        }
    }

    private static GenerationException failure(
            String locale,
            String stage,
            List<String> command,
            Map<String, String> environment,
            File outputDirectory,
            Integer exitStatus,
            String output,
            boolean outputTruncated,
            String reason
    ) {
        return new GenerationException(
                locale,
                stage,
                command,
                environment,
                outputDirectory,
                exitStatus,
                output,
                outputTruncated,
                reason
        );
    }

    private static String printableOutput(ProcessResult result) {
        if (result.output.isEmpty()) return "<empty>";
        return result.output + (result.outputTruncated ? "\n<truncated>" : "");
    }

    interface CommandRunner {
        ProcessResult run(
                List<String> command,
                File workingDirectory,
                Map<String, String> environment
        ) throws IOException, InterruptedException;
    }

    static final class ProcessResult {
        final int exitStatus;
        final String output;
        final boolean outputTruncated;

        ProcessResult(int exitStatus, String output, boolean outputTruncated) {
            this.exitStatus = exitStatus;
            this.output = output != null ? output : "";
            this.outputTruncated = outputTruncated;
        }
    }

    public static final class GenerationException extends IOException {
        private final String locale;
        private final String stage;
        private final List<String> command;
        private final Map<String, String> environment;
        private final File outputDirectory;
        private final Integer exitStatus;
        private final String output;
        private final boolean outputTruncated;

        private GenerationException(
                String locale,
                String stage,
                List<String> command,
                Map<String, String> environment,
                File outputDirectory,
                Integer exitStatus,
                String output,
                boolean outputTruncated,
                String reason
        ) {
            super(message(
                    locale,
                    stage,
                    command,
                    environment,
                    outputDirectory,
                    exitStatus,
                    output,
                    outputTruncated,
                    reason
            ));
            this.locale = locale;
            this.stage = stage;
            this.command = command != null
                    ? new ArrayList<>(command)
                    : new ArrayList<>();
            this.environment = environment != null
                    ? new LinkedHashMap<>(environment)
                    : new LinkedHashMap<>();
            this.outputDirectory = outputDirectory;
            this.exitStatus = exitStatus;
            this.output = output != null ? output : "";
            this.outputTruncated = outputTruncated;
        }

        public String getLocale() {
            return locale;
        }

        public String getStage() {
            return stage;
        }

        public List<String> getCommand() {
            return new ArrayList<>(command);
        }

        public Map<String, String> getEnvironment() {
            return new LinkedHashMap<>(environment);
        }

        public File getOutputDirectory() {
            return outputDirectory;
        }

        public Integer getExitStatus() {
            return exitStatus;
        }

        public String getOutput() {
            return output;
        }

        public boolean isOutputTruncated() {
            return outputTruncated;
        }

        public boolean isOutputComplete() {
            return isComplete(outputDirectory);
        }

        private static String message(
                String locale,
                String stage,
                List<String> command,
                Map<String, String> environment,
                File outputDirectory,
                Integer exitStatus,
                String output,
                boolean outputTruncated,
                String reason
        ) {
            return "Unable to generate runtime locale " + locale
                    + ": " + reason
                    + " stage=" + stage
                    + " exitStatus=" + (exitStatus != null ? exitStatus : "<not-started>")
                    + " complete=" + isComplete(outputDirectory)
                    + " expectedOutput=" + outputDirectory
                    + " argv=" + (command != null ? command : "<not-built>")
                    + " environment=" + (environment != null ? environment : "<not-built>")
                    + " output=" + (
                            output == null || output.isEmpty()
                                    ? "<empty>"
                                    : output + (outputTruncated ? "\n<truncated>" : "")
                    );
        }
    }
}
