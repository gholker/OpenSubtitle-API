package Opensubs;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.xmlrpc.XmlRpcException;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {

    private static final Set<String> possibleExtensions = new HashSet<>();

    static {
        possibleExtensions.add(".mp4");
        possibleExtensions.add(".mkv");
        possibleExtensions.add(".avi");
    }

    private static final Set<String> skippableExtensions = new HashSet<>();

    static {
        skippableExtensions.add(".srt");
        skippableExtensions.add(".txt");
        skippableExtensions.add(".jpg");
        skippableExtensions.add(".DS_Store");
        skippableExtensions.add(".gz");
    }

    private static final Set<String> removableSubstrings = new HashSet<>();

    static {
        removableSubstrings.add("HD");
        removableSubstrings.add("AC");
    }

    public static void main(String[] args) throws IOException, XmlRpcException {
        Options options = new Options();
        options.addOption(Option.builder("u")
                .desc("username")
                .build());
        options.addOption(Option.builder("p")
                .desc("password")
                .build());
        options.addOption(Option.builder("file")
                .hasArg()
                .desc("directory or file to find subtitles for")
                .build());
        options.addOption("F", false, "force re-fetch of subtitles even if one is found (this will overwrite existing .srt files!)");
        CommandLineParser parser = new DefaultParser();
        boolean force = false;
        String root = null;
        String username = "";
        String password = "";
        try {
            CommandLine cmd = parser.parse(options, args);
            force = cmd.hasOption("F");
            root = cmd.getOptionValue("file");
            if (root == null || root.isEmpty()) {
                System.exit(1);
            }
            username = cmd.getOptionValue("u");
            password = cmd.getOptionValue("p");
        } catch (ParseException exp) {
            System.err.println("Parsing failed.  Reason: " + exp.getMessage());
            System.exit(1);
        }


        Path rootPath = Paths.get(root);
        if (!Files.exists(rootPath)) {
            System.out.println("Path does not exist: " + root);
            System.exit(1);
        }

        List<Path> paths;
        if (Files.isRegularFile(rootPath)) {
            paths = Collections.singletonList(rootPath);
        } else {
            paths = Files.list(rootPath).collect(Collectors.toList());
        }

        OpenSubtitle openSubtitle = new OpenSubtitle();
        openSubtitle.login(username, password);

        for (Path p : paths) {
            String filename = p.getFileName().toString();
            int indexOfExtension = filename.lastIndexOf('.');
            if (indexOfExtension > -1) {
                String extension = filename.substring(indexOfExtension);
                if (skippableExtensions.contains(extension)) {
                    continue;
                }

                if (!possibleExtensions.contains(extension)) {
                    System.out.println("Unrecognized extension: " + extension);
                    continue;
                }

                Path subtitlePath = p.getParent().resolve(filename.replace(extension, ".srt"));
                if (!force && Files.exists(subtitlePath)) {
                    System.out.println("Found existing subtitle. Skipping: " + p.toString());
                } else {
                    // attempt hash search
                    List<SubtitleInfo> results = openSubtitle.Search(p.toAbsolutePath().toString());


                    // attempt search by name
                    if (results.isEmpty()) {
                        String season;
                        String episode;
                        Optional<SeasonEpisode> seasonEpisode = findSeasonEpisode(filename);
                        if (seasonEpisode.isPresent()) {
                            season = seasonEpisode.get().season;
                            episode = seasonEpisode.get().episode;
                        } else {
                            season = "";
                            episode = "";
                        }
                        Pattern pattern = Pattern.compile("([^0-9\\W_]*)");
                        String filenameWithoutExtension = filename.replace(extension, "");
                        Matcher matcher = pattern.matcher(filenameWithoutExtension);
                        int index = 0;
                        String query = "";
                        while (index < filenameWithoutExtension.length()
                                && matcher.find(index)) {
                            String group = matcher.group();
                            if (group.length() > 1 && !removableSubstrings.contains(group.toUpperCase())) {
                                query += group + " ";
                            }
                            index = matcher.end() + 1;
                        }
                        query = query.trim();
                        System.out.println("Searching for: `" + query + "` S" + season + "E" + episode);
                        results = openSubtitle.getTvSeriesSubs(
                                query,
                                season,
                                episode,
                                "10",
                                "eng");
                    }


                    Optional<SubtitleInfo> subtitleInfo = results.stream()
                            .filter(i -> i.getLanguageName().toLowerCase().startsWith("eng"))
                            .findFirst();
                    if (subtitleInfo.isPresent()) {
                        SubtitleInfo subtitleInfo1 = subtitleInfo.get();
                        URL url = new URL(subtitleInfo1.getSubDownloadLink().replaceAll("\\.gz", ""));
                        openSubtitle.downloadSubtitle(url, subtitlePath.toString());
                    } else {
                        System.out.println("No subtitle found for: " + p.toString());
                    }
                }
            }

        }

        openSubtitle.logOut();
    }

    public static class SeasonEpisode {
        public final String season;
        public final String episode;

        public SeasonEpisode(String season, String episode) {
            this.season = season;
            this.episode = episode;
        }
    }

    public static Optional<SeasonEpisode> findSeasonEpisode(String filename) {
        Pattern pattern = Pattern.compile("([sS]\\d+[eE]\\d+)");
        Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            String seasonEpisode = matcher.group(1).toLowerCase();
            int indexOfE = seasonEpisode.indexOf('e');
            String season = seasonEpisode.substring(1, indexOfE);
            String episode = seasonEpisode.substring(indexOfE + 1);
            return Optional.of(new SeasonEpisode(season, episode));
        }
        return Optional.empty();
    }
}

