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
import java.util.Arrays;
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

    private static final Set<String> forbiddenWords = new HashSet<>();

    static {
        forbiddenWords.add("ac");
        forbiddenWords.add("hd");
        forbiddenWords.add("season");
        forbiddenWords.add("episode");
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
        options.addOption("H", false, "disable hash search");
        options.addOption("P", false, "include parent folder name in search");
        options.addOption("F", false, "force re-fetch of subtitles even if one is found (this will overwrite existing .srt files!)");
        CommandLineParser parser = new DefaultParser();
        boolean force = false;
        boolean disableHash = true;
        boolean useParentFolderName = false;
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
            disableHash = cmd.hasOption("H");
            useParentFolderName = cmd.hasOption("P");
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
                    System.out.println("File - `" + p.getFileName().toString() + "`");
                    List<SubtitleInfo> results;
                    if (!disableHash) {
                        results = openSubtitle.Search(p.toAbsolutePath().toString());
                        System.out.println("\t" + results.size() + " results from hash search. ");
                    } else {
                        results = Collections.emptyList();
                    }

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

                        String query = "";

                        // start with Folder and Filename
                        String name = filename.replace(extension, "");
                        if (useParentFolderName) {
                            name = p.getParent().getFileName() + " " + name;
                        }

                        // remove non-words
                        {
                            Pattern pattern = Pattern.compile("([^0-9\\W_]*)");
                            Matcher matcher = pattern.matcher(name);
                            int index = 0;
                            while (index < name.length()
                                    && matcher.find(index)) {
                                String group = matcher.group();
                                if (group.length() > 1) {
                                    query += group + " ";
                                }
                                index = matcher.end() + 1;
                            }
                            query = query.trim();
                        }

                        // find `part N`
                        {
                            Pattern pattern = Pattern.compile("([Pp][Aa][Rr][Tt] \\d+)");
                            Matcher matcher = pattern.matcher(filename);
                            if (matcher.find()) {
                                String group = matcher.group();
                                query += " " + group;
                            }
                        }

                        // remove special words
                        query = Arrays.stream(query.split(" "))
                                .filter(word -> !forbiddenWords.contains(word.toLowerCase()))
                                .reduce("", (s, s2) -> s + " " + s2)
                                .trim();

                        System.out.println("\tQuerying: `" + query + "` S" + season + "E" + episode);
                        results = openSubtitle.getTvSeriesSubs(
                                query,
                                season,
                                episode,
                                "10",
                                "eng");
                        System.out.println("\t\t" + results.size() + " results from search. ");
                    }

                    results.forEach(i -> System.out.println("\t\t" + i.getMovieName()));
                    Optional<SubtitleInfo> subtitleInfo = results.stream()
                            .filter(i -> i.getLanguageName().toLowerCase().startsWith("eng"))
                            .findFirst();
                    if (subtitleInfo.isPresent()) {
                        SubtitleInfo subtitleInfo1 = subtitleInfo.get();
                        URL url = new URL(subtitleInfo1.getSubDownloadLink().replaceAll("\\.gz", ""));
                        System.out.print("\tDownloading... ");
                        openSubtitle.downloadSubtitle(url, subtitlePath.toString());
                        System.out.println("DONE");
                    } else {
                        System.out.println("\tNot found");
                    }
                    System.out.println();
                }
            }

        }

        openSubtitle.logOut();
    }

    public static class SeasonEpisode {
        final String season;
        final String episode;

        SeasonEpisode(String season, String episode) {
            this.season = season;
            this.episode = episode;
        }
    }

    private static Optional<SeasonEpisode> findSeasonEpisode(String filename) {
        Pattern pattern = Pattern.compile("([sS]\\d+[xeE]\\d+)");
        Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            String seasonEpisode = matcher.group(1).toLowerCase();
            int indexOfE = Math.max(seasonEpisode.indexOf('e'), seasonEpisode.indexOf('x'));
            String season = seasonEpisode.substring(1, indexOfE);
            String episode = seasonEpisode.substring(indexOfE + 1);
            return Optional.of(new SeasonEpisode(season, episode));
        }
        return Optional.empty();
    }
}

