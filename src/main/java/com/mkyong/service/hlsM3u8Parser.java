package com.mkyong.service;

import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class hlsM3u8Parser {

    private static String unwrapString(String s, char c) {
        return s.replace(c, ' ').trim().replace(' ', c);
    }

    private static String tokenStringToEnumName(String s) {
        return s.replace('-', '_').trim();
    }

    public enum FileType {
        MASTER_PLAYLIST,
        MEDIA_PLAYLIST
    }

    public static final float MEDIA_DURATION_NONE = -1;

    // public static final String FILE_BEGIN_REGEX = "#EXTM3U";
    // public static final String ENTRY_BEGIN_REGEX = "#";
    // public static final String LINE_END_REGEX = "([^\n\r]*)";

    public static final String INTEGER_REGEX = "^\\d+";
    public static final String ENTRY_REGEX = "#([1-9A-Z-]+)(:[@A-Za-z0-9-=,\"._ ]+)?";
    public static final String COMMENT_REGEX = "##(.+)?";

    public static final String CSV_ATTRIBUTES_LIST_REGEX = "\\s*(.+?)\\s*=((?:\".*?\")|.*?)(?:,|$)";

    public static final String ENTRY_SPLIT_CHAR = ":";
    public static final String VALUES_SPLIT_CHAR = ",";
    public static final String ATTRIBUTES_SPLIT_CHAR = "=";
    public static final String RESOLUTION_SPLIT_CHAR = "x";


    public enum EntryType  {
        EXTINF("EXTINF"),
        EXT_X_STREAM_INF("EXT-X-STREAM-INF"),
        EXT_X_MEDIA("EXT-X-MEDIA"),
        EXT_X_BYTERANGE("EXT-X-BYTERANGE"),
        // TODO read values from below entries
        EXTM3U("EXTM3U"),
        EXT_X_VERSION("EXT-X-VERSION"),
        EXT_X_TARGETDURATION("EXT-X-TARGETDURATION"),
        EXT_X_PLAYLIST_TYPE("EXT-X-PLAYLIST-TYPE"),
        EXT_X_ENDLIST("EXT-X-ENDLIST"),
        EXT_X_MAP("EXT-X-MAP"),
        EXT_X_MEDIA_SEQUENCE("EXT_X_MEDIA_SEQUENCE"),
        EXT_X_DISCONTINUITY("EXT_X_DISCONTINUITY");

        private final String token;

        EntryType(String token) {
            this.token = token;
        }

        @Override
        public String toString() {
            return this.token;
        }

        static EntryType fromString(String entryType) {
            // format first to allow input to begin with # and trailing whitespaces
            // also we replace the dash char (-) by underscores (_) in order to
            // be able to make these token valid enum values and identify types.
            entryType = unwrapString(tokenStringToEnumName(entryType), '#');
            try {
                EntryType e = EntryType.valueOf(entryType);
                return e;
            } catch(IllegalArgumentException ex) {
                throw new RuntimeException("Unknown entry type token: " + entryType);
            }
        }

        boolean hasURL() {
            switch (this) {
                case EXTINF:
                case EXT_X_BYTERANGE:
                case EXT_X_STREAM_INF:
                    return true;
                default:
                    return false;
            }
        }
    }


    public enum AttributeType {
        PROGRAM_ID("PROGRAM-ID"),
        BANDWIDTH("BANDWIDTH"),
        CODECS("CODECS"),
        RESOLUTION("RESOLUTION"),
        TYPE("TYPE"),
        GROUP_ID("GROUP-ID"),
        LANGUAGE("LANGUAGE"),
        URI("URI"),
        NAME("NAME"),
        AUDIO("AUDIO"),
        VIDEO("VIDEO"),
        SUBTILES("SUBTITLES");

        private final String attribute;

        AttributeType(String attribute) {
            this.attribute = attribute;
        }

        @Override
        public String toString() {
            return this.attribute;
        }

        static AttributeType fromString(String attribute) {
            try {
                AttributeType a = AttributeType.valueOf(tokenStringToEnumName(attribute));
                return a;
            } catch(IllegalArgumentException ex) {
                throw new RuntimeException("Unknown attribute type token: " + attribute);
            }
        }
    }

    public static class Attribute {
        Attribute(String a) {
            String[] parsedAttribute = a.split(ATTRIBUTES_SPLIT_CHAR);
            if (parsedAttribute.length != 2) {
                throw new RuntimeException("Malformed attribute: " + a);
            }
            this.type = AttributeType.fromString(parsedAttribute[0]);
            this.value = parsedAttribute[1].trim();
        }

        public String toString() {
            return this.type + "=" + this.value;
        }

        public String getValue() {
            switch(this.type) {
                case URI:
                case LANGUAGE:
                case CODECS:
                case AUDIO:
                case VIDEO:
                case SUBTILES:
                case NAME:
                case GROUP_ID:
                    return unwrapString(this.value, '"');
                default:
                    return this.value;
            }
        }

        private final AttributeType type;
        private String value;
    }


    public static class Entry {
        Entry(String e) {
            if (!Entry.couldBe(e)) {
                throw new RuntimeException("Failed to parse malformed entry: " + e);
            }

            // pre: e can be #SOME-TOKEN:XXXX
            // or it can be #SOME-TOKEN and that's it
            String[] parsedEntry;
            if (e.contains(ENTRY_SPLIT_CHAR)) {
                parsedEntry = e.split(ENTRY_SPLIT_CHAR);
            } else {
                parsedEntry = new String[1];
                parsedEntry[0] = e;
            }
            // post: parsedEntry is {#SOME-TOKEN, XXXX} (as applicable)

            // set EntryType from first part (#SOME-TOKEN)
            // note: this method accepts token with #
            this.type = EntryType.fromString(parsedEntry[0]);

            ArrayList<String> valuesList = new ArrayList<>();

            // There are some comma-separated-values behind
            if (parsedEntry.length > 1) {

                String rawValues = parsedEntry[1];

                //log.info(rawValues);

                if (rawValues.contains(VALUES_SPLIT_CHAR)) { // CSV string (may be single value too, see Shaka media playlists)
                    //values = rawValues.split(VALUES_SPLIT_CHAR);

                    Pattern p = Pattern.compile(CSV_ATTRIBUTES_LIST_REGEX);
                    Matcher m = p.matcher(rawValues);
                    while(m.find()) {
                        //log.info("Found CSV list item: " + m.group());
                        valuesList.add(
                                // Note: Match may still contain trailing comma, removing it here
                                unwrapString(m.group(), ',')
                        );
                    }

                    // Didn't match attributes list regex, must be single value with trailing comma
                    // Note: Unfortunately our regex above doesn't match both cases here (simple values and attributes with '=' but
                    // they also usually don't come combined.
                    if (valuesList.size() == 0) {
                        valuesList.add(unwrapString(rawValues, ','));
                    }

                } else { // non-CSV (can only be a single value, faster then matching regex) (maybe could be removed since handled above kind off)
                    valuesList.add(rawValues);
                }

                if (valuesList.size() == 1) {
                    //log.info("Parsed single value: " + valuesList.get(0));
                }
            }

            this.values = valuesList;
        }

        static boolean couldBe(String e) {
            return e.matches(ENTRY_REGEX);
        }

        /**
         * @member Type of entry (#...)
         */
        protected final EntryType type;

        /**
         * @member CSV strings array (values behind the `:`)
         */
        protected final ArrayList<String> values;

        /**
         *
         * @return Attributes array created from current CSV strings array (values)
         */
        Attribute[] readAttributes() {
            if (values.size() <= 1) {
                // better to return an empty array, no need to handle special cases for consumers
                return new Attribute[0];
            }
            // Q: Could be a one-liner with some functional style map method?
            Attribute[] attributes = new Attribute[values.size()];
            for (int i = 0; i < attributes.length; i++) {
                Attribute a = new Attribute(values.get(i));
                attributes[i] = a;
                //log.info("Parsed attribute: " + a.toString());
            }
            return attributes;
        }

        void writeAttributes(Attribute[] attributes) {

        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('#');
            sb.append(this.type.token);
            if (this.values.size() > 0) {
                sb.append(':');
            }
            for (String value : this.values) {
                sb.append(value);
                sb.append(',');
            }
            //sb.append('\n');
            return sb.toString();
        }
    }


    public static class GroupInfoEntry extends Entry {
        GroupInfoEntry(String e) {
            super(e);

            Attribute[] attributes = this.readAttributes();
            for (Attribute a: attributes) {
                switch (a.type) {
                    case GROUP_ID:
                        this.groupId = a.getValue();
                        break;
                    case NAME:
                        this.name = a.getValue();
                        break;
                    case LANGUAGE:
                        this.language = a.getValue();
                        break;
                    case URI:
                        // Q: Do we need to sign this URL too? And should we make this member
                        //    a URL object i.e make this class a URLEntry in this case (since we'd need some context to resolve it)
                        this.uri = a.getValue();
                        break;
                    case TYPE:
                        this.groupType = GroupType.fromString(a.value);
                        break;
                    default:
                        // Maybe we should not throw here as in principle there can be all sorts of attributes
                        // we may not need but we should at least log an error if that occurs...
                        throw new RuntimeException("Unknown attribute type: " + a.type);
                }
            }
        }

        @Override
        public String toString() {
            throw new RuntimeException("not implemented");
        }

        private String groupId = null;
        private String name = null;
        private String language = null;
        private String uri = null;
        private GroupType groupType = null;
    }

    public static enum GroupType {
        AUDIO, VIDEO, SUBTITLES;

        static GroupType fromString(String s) {
            /*
            switch(s) {
                case "AUDIO": return AUDIO;
                case "VIDEO": return VIDEO;
                case "SUBTITLES": return SUBTITLES;
                default: throw new RuntimeException("Invalid group type value: " + s);
            }
            */
            return GroupType.valueOf(s.trim());
        }
    }

    public static class URLEntry extends Entry {
        URLEntry(String e, URL url) {
            super(e);

            this.url = url;
        }

        // Note: This will be absolute. Important to relativize this back (via URI class) against the context of this file
        //       when we serialize
        protected URL url;

        void setUrl(URL url) {
            this.url = url;
        }

        URL getUrl() {
            return this.url;
        }
        /*
        Attribute[] readAttributes() {
            return new Attribute[0];
        }
        */

        /*
        @Override
        public String toString() {
            throw new RuntimeException("not implemented");
        }
        */
    }


    public static class StreamInfoEntry extends URLEntry {
        StreamInfoEntry(String e, URL url) {
            super(e, url);

            Attribute[] attributes = this.readAttributes();
            for (Attribute a: attributes) {
                switch (a.type) {
                    case PROGRAM_ID:
                        this.programId = Integer.parseUnsignedInt(a.value, 10);
                        break;
                    case BANDWIDTH:
                        this.bandwidth = Integer.parseUnsignedInt(a.value, 10);
                        break;
                    case CODECS:
                        this.codecs = a.getValue();
                        this.codecsList = Codec.listFromString(this.codecs);
                        break;
                    case RESOLUTION:
                        this.resolution = Resolution.fromString(a.value);
                        break;
                    case AUDIO:
                        this.audioGroupId = a.getValue();
                        break;
                    case VIDEO:
                        this.videoGroupId = a.getValue();
                        break;
                    case SUBTILES:
                        this.subtitlesGroupId = a.getValue();
                        break;
                    case NAME:
                        this.name = a.getValue();
                        break;
                    default:
                        // Maybe we should not throw here as in principle there can be all sorts of attributes
                        // we may not need but we should at least log an error if that occurs...
                        throw new RuntimeException("Unknown attribute type: " + a.type);
                }
            }
        }

        /*
        @Override
        public String toString() {
            String entry =  EntryType.EXTINF + ":" + this.readAttributes().toString();
            // entry should end with a line-break char
            return entry + '\n' + this.url.toString();
        }
        */

        private int programId = 0;
        private int bandwidth = 0;
        private String codecs = null;
        private String name = null;
        private String audioGroupId = null;
        private String videoGroupId = null;
        private String subtitlesGroupId = null;
        private Resolution resolution = null;
        private ArrayList<Codec> codecsList = null;
    }


    public static class MediaInfoEntry extends URLEntry {
        MediaInfoEntry(String e, URL url) {
            super(e, url);

            if (this.values.size() != 1) {
                throw new RuntimeException("Entry should have exactly one value");
            }

            String floatNumber = this.values.get(0);
            this.duration = Float.parseFloat(floatNumber);
        }

        int addByteRange(Entry e, int offset) {
            if (e.values.size() != 1) {
                throw new RuntimeException("Entry should only have one value");
            }

            String byteRange = e.values.get(0);
            String[] byteRangeParsed = byteRange.split("@");

            if(byteRangeParsed.length > 1) {
                // TODO check spec if we need to accumulate or replace here (what is assumed is the latter)
                offset = Integer.parseUnsignedInt(byteRangeParsed[1]);
            }

            this.byteRangeStart = offset;
            // we need to add this to the offset, then subtract one because
            // this number is meant to be the "end" of the range, so the last byte index inclusively
            // whereas the number we parse here is an amount of bytes in the range, and
            // the offset is also inclusive.
            this.byteRangeEnd = offset + Integer.parseUnsignedInt(byteRangeParsed[0]) - 1;

            return this.byteRangeEnd;
        }

        @Override
        public String toString() {
            String entry = "";
            String temp = EntryType.EXTINF + ":" + duration;
            entry += temp + "\n";
            if (this.byteRangeStart < this.byteRangeEnd) {
                // TODO optimization for serialization output size
                // we could use the "compressed" way to pass on only byte-range lengths
                // based on the previous offset as an assumed start but for this we would
                // need the previous entry context here.
                temp = EntryType.EXT_X_BYTERANGE + ":" + (this.byteRangeEnd - this.byteRangeStart + 1) + "@" + this.byteRangeStart + "\n";
                entry += temp + "\n";
            }

            // entry should end with a line-break char
            return entry + this.url.toString();
        }

        private float duration = MEDIA_DURATION_NONE;
        private int byteRangeStart = 0;
        private int byteRangeEnd = -1;
    }

    public static class Resolution {

        static Resolution fromString(String res) {
            String[] parsedRes = res.split(RESOLUTION_SPLIT_CHAR);
            if (!(parsedRes.length == 2 && parsedRes[0].matches(INTEGER_REGEX) && parsedRes[1].matches(INTEGER_REGEX))) {
                throw new RuntimeException("Malformed resolution: " + res);
            }
            return new Resolution(
                    Integer.parseUnsignedInt(parsedRes[0]),
                    Integer.parseUnsignedInt(parsedRes[1])
            );
        }

        private final int width;
        private final int height;

        Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return width + RESOLUTION_SPLIT_CHAR + height;
        }
    }

    public enum CodecId {
        H264_HIGH_PROFILE_41,
        H264_HIGH_PROFILE_40,
        H264_HIGH_PROFILE_31,
        H264_MAIN_PROFILE_40,
        H264_MAIN_PROFILE_31,
        H264_MAIN_PROFILE_30,
        H264_BASE_PROFILE_31,
        H264_BASE_PROFILE_30,
        H264_BASE_PROFILE_21,
        AAC_LC,
        AAC_HE,
        MP3,
        NOT_IMPLEMENTED;

        static CodecId fromString(String codecString) {
            /**
             * @see https://developer.apple.com/library/content/documentation/NetworkingInternet/Conceptual/StreamingMediaGuide/FrequentlyAskedQuestions/FrequentlyAskedQuestions.html
             * @see https://cconcolato.github.io/media-mime-support/
             *
             */
            switch(codecString) {
                case "mp4a.40.2":
                    return CodecId.AAC_LC;
                case "mp4a.40.5":
                    return CodecId.AAC_HE;

                case "mp4a.40.34":
                    return CodecId.MP3;

                case "avc1.640029":
                    return CodecId.H264_HIGH_PROFILE_41;
                case "avc1.640028":
                    return CodecId.H264_HIGH_PROFILE_40;
                case "avc1.64001f":
                    return CodecId.H264_HIGH_PROFILE_31;

                case "avc1.4d0028":
                    return CodecId.H264_MAIN_PROFILE_40;
                case "avc1.4d001f":
                case "avc1.4d401f": // "constrained" mode
                    return CodecId.H264_MAIN_PROFILE_31;
                case "avc1.4d001e":
                case "avc1.77.30": // iOS v3 compat
                    return CodecId.H264_MAIN_PROFILE_30;

                case "avc1.42001f":
                    return CodecId.H264_BASE_PROFILE_31;
                case "avc1.42001e":
                case "avc1.66.30": // iOS v3 compat
                    return CodecId.H264_BASE_PROFILE_30;
                case "avc1.420016":
                    return CodecId.H264_BASE_PROFILE_21;

                // TODO: Add H265 profiles
                default:
                    return CodecId.NOT_IMPLEMENTED;
            }
        }
    }


    public static class Codec {

        static ArrayList<Codec> listFromString(String codecsString) {
            ArrayList<Codec> codecs = new ArrayList<Codec>();
            String[] parsedCodecs = codecsString.split(",");
            for (String c : parsedCodecs) {
                codecs.add(new Codec(c));
            }
            return codecs;
        }

        public Codec(String codecString) {
            this.id = CodecId.fromString(codecString);
        }

        public Codec(CodecId codecId) {
            this.id = codecId;
        }

        public boolean isAVC() {
            return this.id.name().startsWith("H264");
        }

        public boolean isAAC() {
            return this.id.name().startsWith("AAC");
        }

        public boolean isMP3() {
            return this.id.name().startsWith("MP3");
        }

        private CodecId id;
    }

    public static class ParsingState {
        Entry entry = null;
        MediaInfoEntry mediaInfo = null;
        StreamInfoEntry streamInfo = null;
        GroupInfoEntry groupInfo = null;
        URLEntry urlEntry = null;
        URL url = null;
        boolean expectUrl = false;
    }

    static String readFromInputStream(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private final String data; // Should actually save ref to in stream
    private final URL context;

    private FileType fileType = null;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<MediaInfoEntry> mediaInfoEntries = new ArrayList<>();
    private final ArrayList<StreamInfoEntry> streamInfoEntries = new ArrayList<>();
    private final ArrayList<GroupInfoEntry> groupInfoEntries = new ArrayList<>();

    /**
     * Constructs the object from an input stream and a context URL.
     * @param in Stream that should contain M3U8 data
     * @param context Contextual URL against which we should resolve the references in this file
     * @param parseAfterReading When set, parse() is called from inside the constructor.
     */
    public hlsM3u8Parser(InputStream in, URL context, boolean parseAfterReading) {
        // FIXME: we should consume the stream line by line in the parse method
        this.data = readFromInputStream(in);
        this.context = context;
        if (parseAfterReading) {
            this.parse();
        }
    }

    public void writeTo(java.io.OutputStream out) {

        ////log.info("writeTo");

        PrintWriter pw = new PrintWriter(out);

        for (Entry e: this.entries) {

            String entry = e.toString();

            ////log.info(entry);

            pw.write(entry);
            pw.write('\n');
            pw.flush();
        }
    }

    /**
     * @return True if anything useful could be parsed. When this returns true and parse() is called,
     * the latter results in a no-op. We assume the initial input stream to be readable in finite time.
     */
    public boolean isParsed() {
        return this.entries.size() > 0 && this.fileType != null;
    }

    /**
     * Parses the input stream until the end. We assume the input stream to have an end.
     * Does something when isParsed returns false, otherwise returns immediately.
     * If parse did actually find anything useful, isParsed() will return true and further calls will be no-ops.
     */
    public void parse() {

        ////log.info("Enter parse");

        if (this.isParsed()) {
            ////log.info("Already parsed");
            return;
        }

        //log.info("Data size: " + this.data.length() + " characters");

        // TODO: make scanner read from stream progressively
        Scanner s = new Scanner(this.data);

        int byteRangeOffset = 0;
        ParsingState state = new ParsingState();

        while(s.hasNextLine()) {
            String line = s.nextLine();

            //log.info(line);

            if (state.expectUrl && !Entry.couldBe(line)) { // Should be a URL now here
                //log.info("Extracting URL from context: " + this.context);
                try {
                    state.url = this.context == null ? new URL(line) : new URL(this.context, line);
                } catch(MalformedURLException mue) {
                    throw new RuntimeException("Expected URL but got: " + line + ". Are we missing the context?");
                }

                //log.info("Resolved URL: " + state.url);

                // If we parsed a URL entry, enrich it with that
                if(state.urlEntry != null) {
                    state.urlEntry.setUrl(state.url);
                } else { // Else is an error
                    throw new RuntimeException("Have parsed URL but no corresponding entry exists");
                }

                // Reset parser state and jump to next line
                this.digestParsingState(state);
                state = new ParsingState();
                continue;

            } else if (state.expectUrl && Entry.couldBe(line)) { // We wait for URL but comes another entry
                Entry urlInfoEntry = new Entry(line);

                switch(urlInfoEntry.type) {
                    case EXT_X_BYTERANGE:
                        if (state.mediaInfo == null) {
                            throw new RuntimeException("Assertion failed: An media info entry should be parsed before we read a byte-range entry");
                        }
                        byteRangeOffset = state.mediaInfo.addByteRange(urlInfoEntry, byteRangeOffset);
                        break;
                    default:
                        break;
                }
            } else if (!state.expectUrl && Entry.couldBe(line)) { // A plain and slate entry
                state.entry = new Entry(line);

                //log.info(state.entry.type.name());

                switch(state.entry.type) {
                    case EXTINF:
                        state.entry = state.urlEntry = state.mediaInfo = new MediaInfoEntry(line, state.url);
                        break;
                    case EXT_X_STREAM_INF:
                        state.entry = state.urlEntry = state.streamInfo = new StreamInfoEntry(line, state.url);
                        break;
                    case EXT_X_MEDIA:
                        state.entry = state.groupInfo = new GroupInfoEntry(line);
                        break;
                    default:
                        break;
                }

                if (state.entry.type.hasURL()) {
                    state.expectUrl = true;
                } else {
                    this.digestParsingState(state);
                    state = new ParsingState();
                }

            } else { // Not an entry

                // Valid comment line ?
                if (line.length() > 0 && !line.matches(COMMENT_REGEX)) {
//                    throw new RuntimeException("Line is not a valid entry: " + line);
                    System.out.println("Line is not a valid entry: " + line);
                }
            }
        }

        //log.info("Exit parse");
    }

    public boolean addTrailerToEachURL(String trailer) {
        for (Entry e : this.entries) {
            if (e instanceof URLEntry) {
                URLEntry urlEntry = (URLEntry) e;

                String url = urlEntry.getUrl().toString();
                URL newUrl;
                try {
                    newUrl = new URL(url + trailer);
                } catch(MalformedURLException mue) {
                    //log.error("Failed to append string to URL: " + mue.getMessage());
                    return false;
                }
                urlEntry.setUrl(newUrl);
            }
        }
        return true;
    }

    private void digestParsingState(ParsingState state) {

        //log.info("digestParsingState: " + state.entry.type.toString());

        this.entries.add(state.entry);

        // Q: Maybe we could make all this part a little nicer using down-casting,
        //    but it would create a slight processing overhead as well

        if (state.mediaInfo != null) {
            this.digestFileType(FileType.MEDIA_PLAYLIST);
            this.mediaInfoEntries.add(state.mediaInfo);
        }

        if (state.streamInfo != null) {
            this.digestFileType(FileType.MASTER_PLAYLIST);
            this.streamInfoEntries.add(state.streamInfo);
        }

        if (state.groupInfo != null) {
            this.digestFileType(FileType.MASTER_PLAYLIST);
            this.groupInfoEntries.add(state.groupInfo);
        }
    }

    private void digestFileType(FileType t) {
        if(this.fileType != null && t != this.fileType) {
            throw new RuntimeException("The file-type (master/media) of the m3u8 is ambiguous because of its content");
        }
        else if (this.fileType == null) {
            //log.info("-----> digestFileType: " + t);
            this.fileType = t;
        }
    }


}
