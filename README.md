## OpenSubtitles CLI

Command line tool for finding and download subtitles from OpenSubtitles.org.
 
Find and download subtitles for a movie file from your command line:

`java -jar fetch-subs.jar -u <username> -p <password> -file <path to file or folder>`

Options:

- `-H` disable the search by hash
- `-P` include the parent folder name in the search query
- `-R` recursive
- `-F` force refetch even if a .srt file already exists

Based on the Java client here: https://github.com/sacOO7/OpenSubtitle-API
