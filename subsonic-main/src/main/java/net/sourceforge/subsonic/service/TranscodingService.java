/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.service;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.controller.VideoPlayerController;
import net.sourceforge.subsonic.dao.TranscodingDao;
import net.sourceforge.subsonic.domain.MediaFile;
import net.sourceforge.subsonic.domain.Player;
import net.sourceforge.subsonic.domain.TranscodeScheme;
import net.sourceforge.subsonic.domain.Transcoding;
import net.sourceforge.subsonic.domain.UserSettings;
import net.sourceforge.subsonic.domain.VideoTranscodingSettings;
import net.sourceforge.subsonic.io.TranscodeInputStream;
import net.sourceforge.subsonic.util.StringUtil;
import net.sourceforge.subsonic.util.Util;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Provides services for transcoding media. Transcoding is the process of
 * converting an audio stream to a different format and/or bit rate. The latter is
 * also called downsampling.
 *
 * @author Sindre Mehus
 * @see TranscodeInputStream
 */
public class TranscodingService {

    private static final Logger LOG = Logger.getLogger(TranscodingService.class);

    private TranscodingDao transcodingDao;
    private SettingsService settingsService;
    private PlayerService playerService;

    /**
     * Returns all transcodings.
     *
     * @return Possibly empty list of all transcodings.
     */
    public List<Transcoding> getAllTranscodings() {
        return transcodingDao.getAllTranscodings();
    }

    /**
     * Returns all active transcodings for the given player. Only enabled transcodings are returned.
     *
     * @param player The player.
     * @return All active transcodings for the player.
     */
    public List<Transcoding> getTranscodingsForPlayer(Player player) {
        return transcodingDao.getTranscodingsForPlayer(player.getId());
    }

    /**
     * Sets the list of active transcodings for the given player.
     *
     * @param player         The player.
     * @param transcodingIds ID's of the active transcodings.
     */
    public void setTranscodingsForPlayer(Player player, int[] transcodingIds) {
        transcodingDao.setTranscodingsForPlayer(player.getId(), transcodingIds);
    }

    /**
     * Sets the list of active transcodings for the given player.
     *
     * @param player       The player.
     * @param transcodings The active transcodings.
     */
    public void setTranscodingsForPlayer(Player player, List<Transcoding> transcodings) {
        int[] transcodingIds = new int[transcodings.size()];
        for (int i = 0; i < transcodingIds.length; i++) {
            transcodingIds[i] = transcodings.get(i).getId();
        }
        setTranscodingsForPlayer(player, transcodingIds);
    }


    /**
     * Creates a new transcoding.
     *
     * @param transcoding The transcoding to create.
     */
    public void createTranscoding(Transcoding transcoding) {
        transcodingDao.createTranscoding(transcoding);

        // Activate this transcoding for all players?
        if (transcoding.isDefaultActive()) {
            for (Player player : playerService.getAllPlayers()) {
                List<Transcoding> transcodings = getTranscodingsForPlayer(player);
                transcodings.add(transcoding);
                setTranscodingsForPlayer(player, transcodings);
            }
        }
    }

    /**
     * Deletes the transcoding with the given ID.
     *
     * @param id The transcoding ID.
     */
    public void deleteTranscoding(Integer id) {
        transcodingDao.deleteTranscoding(id);
    }

    /**
     * Updates the given transcoding.
     *
     * @param transcoding The transcoding to update.
     */
    public void updateTranscoding(Transcoding transcoding) {
        transcodingDao.updateTranscoding(transcoding);
    }

    /**
     * Returns whether transcoding is required for the given music file and player combination.
     *
     * @param mediaFile The music file.
     * @param player    The player.
     * @return Whether transcoding  will be performed if invoking the
     *         {@link #getTranscodedInputStream} method with the same arguments.
     */
    public boolean isTranscodingRequired(MediaFile mediaFile, Player player) {
        return getTranscoding(mediaFile, player, null) != null;
    }

    /**
     * Returns the suffix for the given player and music file, taking transcodings into account.
     *
     * @param player                The player in question.
     * @param file                  The music player.
     * @param preferredTargetFormat Used to select among multiple applicable transcodings. May be {@code null}.
     * @return The file suffix, e.g., "mp3".
     */
    public String getSuffix(Player player, MediaFile file, String preferredTargetFormat) {
        Transcoding transcoding = getTranscoding(file, player, preferredTargetFormat);
        return transcoding != null ? transcoding.getTargetFormat() : file.getSuffix();
    }

    /**
     * Creates parameters for a possibly transcoded or downsampled input stream for the given music file and player combination.
     * <p/>
     * A transcoding is applied if it is applicable for the format of the given file, and is activated for the
     * given player.
     * <p/>
     * If no transcoding is applicable, the file may still be downsampled, given that the player is configured
     * with a bit rate limit which is higher than the actual bit rate of the file.
     * <p/>
     * Otherwise, a normal input stream to the original file is returned.
     *
     * @param mediaFile                The music file.
     * @param player                   The player.
     * @param maxBitRate               Overrides the per-player and per-user bitrate limit. May be {@code null}.
     * @param preferredTargetFormat    Used to select among multiple applicable transcodings. May be {@code null}.
     * @param videoTranscodingSettings Parameters used when transcoding video. May be {@code null}.
     * @return Parameters to be used in the {@link #getTranscodedInputStream} method.
     */
    public Parameters getParameters(MediaFile mediaFile, Player player, Integer maxBitRate, String preferredTargetFormat,
                                    VideoTranscodingSettings videoTranscodingSettings) {

        Parameters parameters = new Parameters(mediaFile, videoTranscodingSettings);

        TranscodeScheme transcodeScheme = getTranscodeScheme(player);
        if (maxBitRate == null && transcodeScheme != TranscodeScheme.OFF) {
            maxBitRate = transcodeScheme.getMaxBitRate();
        }

        Transcoding transcoding = getTranscoding(mediaFile, player, preferredTargetFormat);
        if (transcoding != null) {
            parameters.setTranscoding(transcoding);
            if (maxBitRate == null) {
                maxBitRate = mediaFile.isVideo() ? VideoPlayerController.DEFAULT_BIT_RATE: 128;
            }
        } else if (maxBitRate != null) {
            boolean supported = isDownsamplingSupported(mediaFile);
            Integer bitRate = mediaFile.getMetaData().getBitRate();
            if (supported && bitRate != null && bitRate > maxBitRate) {
                parameters.setDownsample(true);
            }
        }

        parameters.setMaxBitRate(maxBitRate);
        return parameters;
    }

    /**
     * Returns a possibly transcoded or downsampled input stream for the given music file and player combination.
     * <p/>
     * A transcoding is applied if it is applicable for the format of the given file, and is activated for the
     * given player.
     * <p/>
     * If no transcoding is applicable, the file may still be downsampled, given that the player is configured
     * with a bit rate limit which is higher than the actual bit rate of the file.
     * <p/>
     * Otherwise, a normal input stream to the original file is returned.
     *
     * @param parameters As returned by {@link #getParameters}.
     * @return A possible transcoded or downsampled input stream.
     * @throws IOException If an I/O error occurs.
     */
    public InputStream getTranscodedInputStream(Parameters parameters) throws IOException {
        try {

            if (parameters.getTranscoding() != null) {
                return createTranscodedInputStream(parameters);
            }

            if (parameters.downsample) {
                return createDownsampledInputStream(parameters);
            }

        } catch (Exception x) {
            LOG.warn("Failed to transcode " + parameters.getmediaFile() + ". Using original.", x);
        }

        return new FileInputStream(parameters.getmediaFile().getFile());
    }


    /**
     * Returns the strictest transcoding scheme defined for the player and the user.
     */
    private TranscodeScheme getTranscodeScheme(Player player) {
        String username = player.getUsername();
        if (username != null) {
            UserSettings userSettings = settingsService.getUserSettings(username);
            return player.getTranscodeScheme().strictest(userSettings.getTranscodeScheme());
        }

        return player.getTranscodeScheme();
    }

    /**
     * Returns an input stream by applying the given transcoding to the given music file.
     *
     * @param parameters Transcoding parameters.
     * @return The transcoded input stream.
     * @throws IOException If an I/O error occurs.
     */
    private InputStream createTranscodedInputStream(Parameters parameters)
            throws IOException {

        Transcoding transcoding = parameters.getTranscoding();
        Integer maxBitRate = parameters.getMaxBitRate();
        VideoTranscodingSettings videoTranscodingSettings = parameters.getVideoTranscodingSettings();
        MediaFile mediaFile = parameters.getmediaFile();

        TranscodeInputStream in = createTranscodeInputStream(transcoding.getStep1(), maxBitRate, videoTranscodingSettings, mediaFile, null);

        if (transcoding.getStep2() != null) {
            in = createTranscodeInputStream(transcoding.getStep2(), maxBitRate, videoTranscodingSettings, mediaFile, in);
        }

        if (transcoding.getStep3() != null) {
            in = createTranscodeInputStream(transcoding.getStep3(), maxBitRate, videoTranscodingSettings, mediaFile, in);
        }

        return in;
    }

    /**
     * Creates a transcoded input stream by interpreting the given command line string.
     * This includes the following:
     * <ul>
     * <li>Splitting the command line string to an array.</li>
     * <li>Replacing occurrences of "%s" with the path of the given music file.</li>
     * <li>Replacing occurrences of "%t" with the title of the given music file.</li>
     * <li>Replacing occurrences of "%l" with the album name of the given music file.</li>
     * <li>Replacing occurrences of "%a" with the artist name of the given music file.</li>
     * <li>Replacing occurrcences of "%b" with the max bitrate.</li>
     * <li>Replacing occurrcences of "%o" with the video time offset (used for scrubbing).</li>
     * <li>Replacing occurrcences of "%w" with the video image width.</li>
     * <li>Replacing occurrcences of "%h" with the video image height.</li>
     * <li>Prepending the path of the transcoder directory if the transcoder is found there.</li>
     * </ul>
     *
     * @param command                  The command line string.
     * @param maxBitRate               The maximum bitrate to use. May not be {@code null}.
     * @param videoTranscodingSettings Parameters used when transcoding video. May be {@code null}.
     * @param mediaFile                The music file to use when replacing "%s" etc.
     * @param in                       Data to feed to the process.  May be {@code null}.
     * @return The newly created input stream.
     */
    private TranscodeInputStream createTranscodeInputStream(String command, Integer maxBitRate,
                                                            VideoTranscodingSettings videoTranscodingSettings, MediaFile mediaFile, InputStream in) throws IOException {

        String title = mediaFile.getMetaData().getTitle();
        String album = mediaFile.getMetaData().getAlbum();
        String artist = mediaFile.getMetaData().getArtist();

        if (title == null) {
            title = "Unknown Song";
        }
        if (album == null) {
            title = "Unknown Album";
        }
        if (artist == null) {
            title = "Unknown Artist";
        }

        List<String> result = new LinkedList<String>(Arrays.asList(StringUtil.split(command)));
        result.set(0, getTranscodeDirectory().getPath() + File.separatorChar + result.get(0));

        File tmpFile = null;

        for (int i = 1; i < result.size(); i++) {
            String cmd = result.get(i);
            if (cmd.contains("%b")) {
                cmd = cmd.replace("%b", String.valueOf(maxBitRate));
            }
            if (cmd.contains("%t")) {
                cmd = cmd.replace("%t", title);
            }
            if (cmd.contains("%l")) {
                cmd = cmd.replace("%l", album);
            }
            if (cmd.contains("%a")) {
                cmd = cmd.replace("%a", artist);
            }
            if (cmd.contains("%o") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%o", String.valueOf(videoTranscodingSettings.getTimeOffset()));
            }
            if (cmd.contains("%w") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%w", String.valueOf(videoTranscodingSettings.getWidth()));
            }
            if (cmd.contains("%h") && videoTranscodingSettings != null) {
                cmd = cmd.replace("%h", String.valueOf(videoTranscodingSettings.getHeight()));
            }
            if (cmd.contains("%s")) {

                // Work-around for filename character encoding problem on Windows.
                // Create temporary file, and feed this to the transcoder.
                String path = mediaFile.getFile().getAbsolutePath();
                if (Util.isWindows() && !mediaFile.isVideo() && !StringUtils.isAsciiPrintable(path)) {
                    tmpFile = File.createTempFile("subsonic", "." + FilenameUtils.getExtension(path));
                    tmpFile.deleteOnExit();
                    FileUtils.copyFile(new File(path), tmpFile);
                    LOG.debug("Created tmp file: " + tmpFile);
                    cmd = cmd.replace("%s", tmpFile.getPath());
                } else {
                    cmd = cmd.replace("%s", path);
                }
            }

            result.set(i, cmd);
        }
        return new TranscodeInputStream(new ProcessBuilder(result), in, tmpFile);
    }

    /**
     * Returns an applicable transcoding for the given file and player, or <code>null</code> if no
     * transcoding should be done.
     */
    private Transcoding getTranscoding(MediaFile mediaFile, Player player, String preferredTargetFormat) {

        List<Transcoding> applicableTranscodings = new LinkedList<Transcoding>();
        String suffix = mediaFile.getSuffix();

        for (Transcoding transcoding : getTranscodingsForPlayer(player)) {
            for (String sourceFormat : transcoding.getSourceFormatsAsArray()) {
                if (sourceFormat.equalsIgnoreCase(suffix)) {
                    if (isTranscodingInstalled(transcoding)) {
                        applicableTranscodings.add(transcoding);
                    }
                }
            }
        }

        if (applicableTranscodings.isEmpty()) {
            return null;
        }

        for (Transcoding transcoding : applicableTranscodings) {
            if (transcoding.getTargetFormat().equalsIgnoreCase(preferredTargetFormat)) {
                return transcoding;
            }
        }

        return applicableTranscodings.get(0);
    }

    /**
     * Returns a downsampled input stream to the music file.
     *
     * @param parameters Downsample parameters.
     * @throws IOException If an I/O error occurs.
     */
    private InputStream createDownsampledInputStream(Parameters parameters) throws IOException {
        String command = settingsService.getDownsamplingCommand();
        return createTranscodeInputStream(command, parameters.getMaxBitRate(), parameters.getVideoTranscodingSettings(),
                parameters.getmediaFile(), null);
    }

    /**
     * Returns whether downsampling is supported (i.e., whether LAME is installed or not.)
     *
     * @param mediaFile If not null, returns whether downsampling is supported for this file.
     * @return Whether downsampling is supported.
     */
    public boolean isDownsamplingSupported(MediaFile mediaFile) {
        if (mediaFile != null) {
            boolean isMp3 = "mp3".equalsIgnoreCase(mediaFile.getSuffix());
            if (!isMp3) {
                return false;
            }
        }

        String commandLine = settingsService.getDownsamplingCommand();
        return isTranscodingStepInstalled(commandLine);
    }

    private boolean isTranscodingInstalled(Transcoding transcoding) {
        return isTranscodingStepInstalled(transcoding.getStep1()) &&
                isTranscodingStepInstalled(transcoding.getStep2()) &&
                isTranscodingStepInstalled(transcoding.getStep3());
    }

    private boolean isTranscodingStepInstalled(String step) {
        if (StringUtils.isEmpty(step)) {
            return true;
        }
        String executable = StringUtil.split(step)[0];
        PrefixFileFilter filter = new PrefixFileFilter(executable);
        String[] matches = getTranscodeDirectory().list(filter);
        return matches != null && matches.length > 0;
    }

    /**
     * Returns the directory in which all transcoders are installed.
     */
    public File getTranscodeDirectory() {
        File dir = new File(SettingsService.getSubsonicHome(), "transcode");
        if (!dir.exists()) {
            boolean ok = dir.mkdir();
            if (ok) {
                LOG.info("Created directory " + dir);
            } else {
                LOG.warn("Failed to create directory " + dir);
            }
        }
        return dir;
    }

    public void setTranscodingDao(TranscodingDao transcodingDao) {
        this.transcodingDao = transcodingDao;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setPlayerService(PlayerService playerService) {
        this.playerService = playerService;
    }

    public static class Parameters {
        private boolean downsample;
        private final MediaFile mediaFile;
        private final VideoTranscodingSettings videoTranscodingSettings;
        private Integer maxBitRate;
        private Transcoding transcoding;

        public Parameters(MediaFile mediaFile, VideoTranscodingSettings videoTranscodingSettings) {
            this.mediaFile = mediaFile;
            this.videoTranscodingSettings = videoTranscodingSettings;
        }

        public void setMaxBitRate(Integer maxBitRate) {
            this.maxBitRate = maxBitRate;
        }

        public boolean isDownsample() {
            return downsample;
        }

        public void setDownsample(boolean downsample) {
            this.downsample = downsample;
        }

        public boolean isTranscode() {
            return transcoding != null;
        }

        public void setTranscoding(Transcoding transcoding) {
            this.transcoding = transcoding;
        }

        public Transcoding getTranscoding() {
            return transcoding;
        }

        public MediaFile getmediaFile() {
            return mediaFile;
        }

        public Integer getMaxBitRate() {
            return maxBitRate;
        }

        public VideoTranscodingSettings getVideoTranscodingSettings() {
            return videoTranscodingSettings;
        }
    }
}
