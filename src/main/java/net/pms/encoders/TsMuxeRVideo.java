/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import net.pms.Messages;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.*;
import net.pms.formats.Format;
import net.pms.io.*;
import net.pms.util.CodecUtil;
import net.pms.util.FormLayoutUtil;
import net.pms.util.PlayerUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.*;
import java.net.URL;
import java.util.Locale;

import static net.pms.formats.v2.AudioUtils.getLPCMChannelMappingForMencoder;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.startsWith;

public class TsMuxeRVideo extends Player {
	private static final Logger logger = LoggerFactory.getLogger(TsMuxeRVideo.class);
	private static final String COL_SPEC = "left:pref, 0:grow";
	private static final String ROW_SPEC = "p, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu, 0:grow";

	public static final String ID = "tsmuxer";
	private PmsConfiguration configuration;

	public TsMuxeRVideo(PmsConfiguration configuration) {
		this.configuration = configuration;
	}

	@Override
	public boolean excludeFormat(Format format) {
		String extension = format.getMatchedExtension();
		return extension != null
			&& !extension.equals("mp4")
			&& !extension.equals("mkv")
			&& !extension.equals("ts")
			&& !extension.equals("tp")
			&& !extension.equals("m2ts")
			&& !extension.equals("m2t")
			&& !extension.equals("mpg")
			&& !extension.equals("evo")
			&& !extension.equals("mpeg")
			&& !extension.equals("vob")
			&& !extension.equals("m2v")
			&& !extension.equals("mts")
			&& !extension.equals("mov");
	}

	@Override
	public PlayerPurpose getPurpose() {
		return PlayerPurpose.VIDEO_FILE_PLAYER;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean isTimeSeekable() {
		return true;
	}

	@Override
	public String[] args() {
		return null;
	}

	@Override
	public String executable() {
		return configuration.getTsmuxerPath();
	}

	@Override
	public ProcessWrapper launchTranscode(
		DLNAResource dlna,
		DLNAMediaInfo media,
		OutputParams params
	) throws IOException {
		final String filename = dlna.getSystemName();
		setAudioAndSubs(filename, media, params, configuration);

		PipeIPCProcess ffVideoPipe;
		ProcessWrapperImpl ffVideo;

		PipeIPCProcess ffAudioPipe[] = null;
		ProcessWrapperImpl ffAudio[] = null;

		String fps = media.getValidFps(false);

		int width  = media.getWidth();
		int height = media.getHeight();
		if (width < 320 || height < 240) {
			width  = -1;
			height = -1;
		}

		String videoType = "V_MPEG4/ISO/AVC";
		if (startsWith(media.getCodecV(), "mpeg2")) {
			videoType = "V_MPEG-2";
		}

		if (this instanceof TsMuxeRAudio && media.getFirstAudioTrack() != null) {
			String fakeFileName = writeResourceToFile("/resources/images/fake.jpg");
			ffVideoPipe = new PipeIPCProcess(System.currentTimeMillis() + "fakevideo", System.currentTimeMillis() + "videoout", false, true);

			String timeEndValue1 = "-t";
			String timeEndValue2 = "" + params.timeend;
			if (params.timeend < 1) {
				timeEndValue1 = "-y";
				timeEndValue2 = "-y";
			}

			String[] ffmpegLPCMextract = new String[] {
				configuration.getFfmpegPath(),
				timeEndValue1, timeEndValue2,
				"-loop", "1",
				"-i", fakeFileName,
				"-qcomp", "0.6",
				"-qmin", "10",
				"-qmax", "51",
				"-qdiff", "4",
				"-me_range", "4",
				"-f", "h264",
				"-vcodec", "libx264",
				"-an",
				"-y",
				ffVideoPipe.getInputPipe()
			};

			videoType = "V_MPEG4/ISO/AVC";

			OutputParams ffparams = new OutputParams(configuration);
			ffparams.maxBufferSize = 1;
			ffVideo = new ProcessWrapperImpl(ffmpegLPCMextract, ffparams);

			if (
				filename.toLowerCase().endsWith(".flac") &&
				media.getFirstAudioTrack().getBitsperSample() >= 24 &&
				media.getFirstAudioTrack().getSampleRate() % 48000 == 0
			) {
				ffAudioPipe = new PipeIPCProcess[1];
				ffAudioPipe[0] = new PipeIPCProcess(System.currentTimeMillis() + "flacaudio", System.currentTimeMillis() + "audioout", false, true);

				String[] flacCmd = new String[] {
					configuration.getFlacPath(),
					"--output-name=" + ffAudioPipe[0].getInputPipe(),
					"-d",
					"-f",
					"-F",
					filename
				};

				ffparams = new OutputParams(configuration);
				ffparams.maxBufferSize = 1;
				ffAudio = new ProcessWrapperImpl[1];
				ffAudio[0] = new ProcessWrapperImpl(flacCmd, ffparams);
			} else {
				ffAudioPipe = new PipeIPCProcess[1];
				ffAudioPipe[0] = new PipeIPCProcess(System.currentTimeMillis() + "mlpaudio", System.currentTimeMillis() + "audioout", false, true);
				String depth = "pcm_s16le";
				String rate = "48000";

				if (media.getFirstAudioTrack().getBitsperSample() >= 24) {
					depth = "pcm_s24le";
				}

				if (media.getFirstAudioTrack().getSampleRate() > 48000) {
					rate = "" + media.getFirstAudioTrack().getSampleRate();
				}

				String[] flacCmd = new String[] {
					configuration.getFfmpegPath(),
					"-i", filename,
					"-ar", rate,
					"-f", "wav",
					"-acodec", depth,
					"-y",
					ffAudioPipe[0].getInputPipe()
				};

				ffparams = new OutputParams(configuration);
				ffparams.maxBufferSize = 1;
				ffAudio = new ProcessWrapperImpl[1];
				ffAudio[0] = new ProcessWrapperImpl(flacCmd, ffparams);
			}
		} else {
			params.waitbeforestart = 5000;
			params.manageFastStart();

			String mencoderPath = configuration.getMencoderPath();

			ffVideoPipe = new PipeIPCProcess(System.currentTimeMillis() + "ffmpegvideo", System.currentTimeMillis() + "videoout", false, true);

			// Special handling for evo files
			String evoValue1 = "-quiet";
			String evoValue2 = "-quiet";
			if (filename.toLowerCase().endsWith(".evo")) {
				evoValue1 = "-psprobe";
				evoValue2 = "1000000";
			}

			String[] ffmpegLPCMextract = new String[] {
				mencoderPath,
				"-ss", params.timeseek > 0 ? "" + params.timeseek : "0",
				params.stdin != null ? "-" : filename,
				evoValue1, evoValue2,
				"-really-quiet",
				"-msglevel", "statusline=2",
				"-ovc", "copy",
				"-nosound",
				"-mc", "0",
				"-noskip",
				"-of", "rawvideo",
				"-o", ffVideoPipe.getInputPipe()
			};

			InputFile newInput = new InputFile();
			newInput.setFilename(filename);
			newInput.setPush(params.stdin);

			if (media != null) {
				// TODO useful only for logging
				final boolean compat = media.isMediaparsed()
						&& ((newInput != null && media.isVideoWithinH264LevelLimits(newInput, params.mediaRenderer))
						|| !params.mediaRenderer.isH264Level41Limited());

				if (!compat && params.mediaRenderer.isPS3()) {
					logger.info("The video will not play or will show a black screen on the PS3...");
				}

				if (media.getH264AnnexB() != null && media.getH264AnnexB().length > 0) {
					StreamModifier sm = new StreamModifier();
					sm.setHeader(media.getH264AnnexB());
					sm.setH264AnnexB(true);
					ffVideoPipe.setModifier(sm);
				}
			}

			OutputParams ffparams = new OutputParams(configuration);
			ffparams.maxBufferSize = 1;
			ffparams.stdin = params.stdin;
			ffVideo = new ProcessWrapperImpl(ffmpegLPCMextract, ffparams);

			int numAudioTracks = 1;

			if (media.getAudioTracksList() != null && media.getAudioTracksList().size() > 1 && configuration.isMuxAllAudioTracks()) {
				numAudioTracks = media.getAudioTracksList().size();
			}

			boolean singleMediaAudio = media.getAudioTracksList().size() <= 1;

			if (params.aid != null) {
				boolean ac3Remux;
				boolean dtsRemux;
				boolean pcm;

				// Disable LPCM transcoding for MP4 container with non-H264 video as workaround for MEncoder's A/V sync bug
				boolean mp4_with_non_h264 = (media.getContainer().equals("mp4") && !media.getCodecV().equals("h264"));

				if (numAudioTracks <= 1) {
					ffAudioPipe = new PipeIPCProcess[numAudioTracks];
					ffAudioPipe[0] = new PipeIPCProcess(System.currentTimeMillis() + "ffmpegaudio01", System.currentTimeMillis() + "audioout", false, true);

					/*
					 Disable AC-3 remux for stereo tracks with 384 kbits bitrate and PS3 renderer (PS3 FW bug?)
					 TODO check new firmwares
					 Commented out until we can find a way to detect when a video has an audio track that switches from 2 to 6 channels
					 because MEncoder can't handle those files, which are very common these days.
					*/
					// final boolean ps3_and_stereo_and_384_kbits = (params.mediaRenderer.isPS3() && params.aid.getAudioProperties().getNumberOfChannels() == 2)
					//	&& (params.aid.getBitRate() > 370000 && params.aid.getBitRate() < 400000);
					final boolean ps3_and_stereo_and_384_kbits = false;
					ac3Remux = (params.aid.isAC3() && !ps3_and_stereo_and_384_kbits && configuration.isAudioRemuxAC3());
					dtsRemux = configuration.isAudioEmbedDtsInPcm() && params.aid.isDTS() && params.mediaRenderer.isDTSPlayable();

					pcm = configuration.isAudioUsePCM() &&
						!mp4_with_non_h264 &&
						(
							params.aid.isLossless() ||
							(params.aid.isDTS() && params.aid.getAudioProperties().getNumberOfChannels() <= 6) ||
							params.aid.isTrueHD() ||
							(
								!configuration.isMencoderUsePcmForHQAudioOnly() &&
								(
									params.aid.isAC3() ||
									params.aid.isMP3() ||
									params.aid.isAAC() ||
									params.aid.isVorbis() ||
									// params.aid.isWMA() ||
									params.aid.isMpegAudio()
								)
							)
						) && params.mediaRenderer.isLPCMPlayable();

					int channels;
					if (ac3Remux) {
						channels = params.aid.getAudioProperties().getNumberOfChannels(); // AC-3 remux
					} else if (dtsRemux) {
						channels = 2;
					} else if (pcm) {
						channels = params.aid.getAudioProperties().getNumberOfChannels();
					} else {
						channels = configuration.getAudioChannelCount(); // 5.1 max for AC-3 encoding
					}

					if (!ac3Remux && (dtsRemux || pcm)) {
						// DTS remux or LPCM
						StreamModifier sm = new StreamModifier();
						sm.setPcm(pcm);
						sm.setDtsEmbed(dtsRemux);
						sm.setNbChannels(channels);
						sm.setSampleFrequency(params.aid.getSampleRate() < 48000 ? 48000 : params.aid.getSampleRate());
						sm.setBitsPerSample(16);

						String mixer = null;

						if (pcm && !dtsRemux) {
							mixer = getLPCMChannelMappingForMencoder(params.aid);
						}

						ffmpegLPCMextract = new String[] {
							mencoderPath,
							"-ss", params.timeseek > 0 ? "" + params.timeseek : "0",
							params.stdin != null ? "-" : filename,
							evoValue1, evoValue2,
							"-really-quiet",
							"-msglevel", "statusline=2",
							"-channels", "" + sm.getNbChannels(),
							"-ovc", "copy",
							"-of", "rawaudio",
							"-mc", sm.isDtsEmbed() ? "0.1" : "0",
							"-noskip",
							"-oac", sm.isDtsEmbed() ? "copy" : "pcm",
							isNotBlank(mixer) ? "-af" : "-quiet", isNotBlank(mixer) ? mixer : "-quiet",
							singleMediaAudio ? "-quiet" : "-aid", singleMediaAudio ? "-quiet" : ("" + params.aid.getId()),
							"-srate", "48000",
							"-o", ffAudioPipe[0].getInputPipe()
						};

						// Use PCM trick when media renderer does not support DTS in MPEG
						if (!params.mediaRenderer.isMuxDTSToMpeg()) {
							ffAudioPipe[0].setModifier(sm);
						}
					} else {
						// AC-3 remux or encoding
						ffmpegLPCMextract = new String[] {
							mencoderPath,
							"-ss", params.timeseek > 0 ? "" + params.timeseek : "0",
							params.stdin != null ? "-" : filename,
							evoValue1, evoValue2,
							"-really-quiet",
							"-msglevel", "statusline=2",
							"-channels", "" + channels,
							"-ovc", "copy",
							"-of", "rawaudio",
							"-mc", "0",
							"-noskip",
							"-oac", (ac3Remux) ? "copy" : "lavc",
							params.aid.isAC3() ? "-fafmttag" : "-quiet", params.aid.isAC3() ? "0x2000" : "-quiet",
							"-lavcopts", "acodec=" + (configuration.isMencoderAc3Fixed() ? "ac3_fixed" : "ac3") + ":abitrate=" + CodecUtil.getAC3Bitrate(configuration, params.aid),
							"-af", "lavcresample=48000",
							"-srate", "48000",
							singleMediaAudio ? "-quiet" : "-aid", singleMediaAudio ? "-quiet" : ("" + params.aid.getId()),
							"-o", ffAudioPipe[0].getInputPipe()
						};
					}

					ffparams = new OutputParams(configuration);
					ffparams.maxBufferSize = 1;
					ffparams.stdin = params.stdin;
					ffAudio = new ProcessWrapperImpl[numAudioTracks];
					ffAudio[0] = new ProcessWrapperImpl(ffmpegLPCMextract, ffparams);
				} else {
					ffAudioPipe = new PipeIPCProcess[numAudioTracks];
					ffAudio = new ProcessWrapperImpl[numAudioTracks];
					for (int i = 0; i < media.getAudioTracksList().size(); i++) {
						DLNAMediaAudio audio = media.getAudioTracksList().get(i);
						ffAudioPipe[i] = new PipeIPCProcess(System.currentTimeMillis() + "ffmpeg" + i, System.currentTimeMillis() + "audioout" + i, false, true);

						/*
						 Disable AC-3 remux for stereo tracks with 384 kbits bitrate and PS3 renderer (PS3 FW bug?)
					 	 TODO check new firmwares
					 	 Commented out until we can find a way to detect when a video has an audio track that switches from 2 to 6 channels
					 	 because MEncoder can't handle those files, which are very common these days.
					 	*/
						// final boolean ps3_and_stereo_and_384_kbits = (params.mediaRenderer.isPS3() && audio.getAudioProperties().getNumberOfChannels() == 2)
						//	&& (audio.getBitRate() > 370000 && audio.getBitRate() < 400000);
						final boolean ps3_and_stereo_and_384_kbits = false;
						ac3Remux = audio.isAC3() && !ps3_and_stereo_and_384_kbits && configuration.isAudioRemuxAC3();
						dtsRemux = configuration.isAudioEmbedDtsInPcm() && audio.isDTS() && params.mediaRenderer.isDTSPlayable();

						pcm = configuration.isAudioUsePCM() &&
							!mp4_with_non_h264 &&
							(
								audio.isLossless() ||
								(audio.isDTS() && audio.getAudioProperties().getNumberOfChannels() <= 6) ||
								audio.isTrueHD() ||
								(
									!configuration.isMencoderUsePcmForHQAudioOnly() &&
									(
										audio.isAC3() ||
										audio.isMP3() ||
										audio.isAAC() ||
										audio.isVorbis() ||
										// audio.isWMA() ||
										audio.isMpegAudio()
									)
								)
							) && params.mediaRenderer.isLPCMPlayable();

						int channels;
						if (ac3Remux) {
							channels = audio.getAudioProperties().getNumberOfChannels(); // AC-3 remux
						} else if (dtsRemux) {
							channels = 2;
						} else if (pcm) {
							channels = audio.getAudioProperties().getNumberOfChannels();
						} else {
							channels = configuration.getAudioChannelCount(); // 5.1 max for AC-3 encoding
						}

						if (!ac3Remux && (dtsRemux || pcm)) {
							// DTS remux or LPCM
							StreamModifier sm = new StreamModifier();
							sm.setPcm(pcm);
							sm.setDtsEmbed(dtsRemux);
							sm.setNbChannels(channels);
							sm.setSampleFrequency(audio.getSampleRate() < 48000 ? 48000 : audio.getSampleRate());
							sm.setBitsPerSample(16);
							if (!params.mediaRenderer.isMuxDTSToMpeg()) {
								ffAudioPipe[i].setModifier(sm);
							}

							String mixer = null;
							if (pcm && !dtsRemux) {
								mixer = getLPCMChannelMappingForMencoder(audio);
							}

							ffmpegLPCMextract = new String[]{
								mencoderPath,
								"-ss", params.timeseek > 0 ? "" + params.timeseek : "0",
								params.stdin != null ? "-" : filename,
								evoValue1, evoValue2,
								"-really-quiet",
								"-msglevel", "statusline=2",
								"-channels", "" + sm.getNbChannels(),
								"-ovc", "copy",
								"-of", "rawaudio",
								"-mc", sm.isDtsEmbed() ? "0.1" : "0",
								"-noskip",
								"-oac", sm.isDtsEmbed() ? "copy" : "pcm",
								isNotBlank(mixer) ? "-af" : "-quiet", isNotBlank(mixer) ? mixer : "-quiet",
								singleMediaAudio ? "-quiet" : "-aid", singleMediaAudio ? "-quiet" : ("" + audio.getId()),
								"-srate", "48000",
								"-o", ffAudioPipe[i].getInputPipe()
							};
						} else {
							// AC-3 remux or encoding
							ffmpegLPCMextract = new String[]{
								mencoderPath,
								"-ss", params.timeseek > 0 ? "" + params.timeseek : "0",
								params.stdin != null ? "-" : filename,
								evoValue1, evoValue2,
								"-really-quiet",
								"-msglevel", "statusline=2",
								"-channels", "" + channels,
								"-ovc", "copy",
								"-of", "rawaudio",
								"-mc", "0",
								"-noskip",
								"-oac", (ac3Remux) ? "copy" : "lavc",
								audio.isAC3() ? "-fafmttag" : "-quiet", audio.isAC3() ? "0x2000" : "-quiet",
								"-lavcopts", "acodec=" + (configuration.isMencoderAc3Fixed() ? "ac3_fixed" : "ac3") + ":abitrate=" + CodecUtil.getAC3Bitrate(configuration, audio),
								"-af", "lavcresample=48000",
								"-srate", "48000",
								singleMediaAudio ? "-quiet" : "-aid", singleMediaAudio ? "-quiet" : ("" + audio.getId()),
								"-o", ffAudioPipe[i].getInputPipe()
							};
						}

						ffparams = new OutputParams(configuration);
						ffparams.maxBufferSize = 1;
						ffparams.stdin = params.stdin;
						ffAudio[i] = new ProcessWrapperImpl(ffmpegLPCMextract, ffparams);
					}
				}
			}
		}

		File f = new File(configuration.getTempFolder(), "pms-tsmuxer.meta");
		params.log = false;
		PrintWriter pw = new PrintWriter(f);
		pw.print("MUXOPT --no-pcr-on-video-pid");
		pw.print(" --new-audio-pes");
		pw.print(" --no-asyncio");
		pw.print(" --vbr");
		pw.println(" --vbv-len=500");

			String videoparams = "level=4.1, insertSEI, contSPS, track=1";
			if (this instanceof TsMuxeRAudio) {
				videoparams = "track=224";
			}
			if (configuration.isFix25FPSAvMismatch()) {
				fps = "25";
			}
			pw.println(videoType + ", \"" + ffVideoPipe.getOutputPipe() + "\", " + (fps != null ? ("fps=" + fps + ", ") : "") + (width != -1 ? ("video-width=" + width + ", ") : "") + (height != -1 ? ("video-height=" + height + ", ") : "") + videoparams);

		// Disable LPCM transcoding for MP4 container with non-H264 video as workaround for mencoder's A/V sync bug
		boolean mp4_with_non_h264 = (media.getContainer().equals("mp4") && !media.getCodecV().equals("h264"));
		if (ffAudioPipe != null && ffAudioPipe.length == 1) {
			String timeshift = "";
			boolean ac3Remux;
			boolean dtsRemux;
			boolean pcm;
			/*
			 Disable AC-3 remux for stereo tracks with 384 kbits bitrate and PS3 renderer (PS3 FW bug?)
			 TODO check new firmwares
			 Commented out until we can find a way to detect when a video has an audio track that switches from 2 to 6 channels
			 because MEncoder can't handle those files, which are very common these days.
			*/
			// final boolean ps3_and_stereo_and_384_kbits = (params.mediaRenderer.isPS3() && params.aid.getAudioProperties().getNumberOfChannels() == 2)
			//	&& (params.aid.getBitRate() > 370000 && params.aid.getBitRate() < 400000);
			final boolean ps3_and_stereo_and_384_kbits = false;
			ac3Remux = params.aid.isAC3() && !ps3_and_stereo_and_384_kbits && configuration.isAudioRemuxAC3();
			dtsRemux = configuration.isAudioEmbedDtsInPcm() && params.aid.isDTS() && params.mediaRenderer.isDTSPlayable();
			pcm = configuration.isAudioUsePCM() &&
				!mp4_with_non_h264 &&
				(
					params.aid.isLossless() ||
					(params.aid.isDTS() && params.aid.getAudioProperties().getNumberOfChannels() <= 6) ||
					params.aid.isTrueHD() ||
					(
						!configuration.isMencoderUsePcmForHQAudioOnly() &&
						(
							params.aid.isAC3() ||
							params.aid.isMP3() ||
							params.aid.isAAC() ||
							params.aid.isVorbis() ||
							// params.aid.isWMA() ||
							params.aid.isMpegAudio()
						)
					)
				) && params.mediaRenderer.isLPCMPlayable();
			String type = "A_AC3";
			if (ac3Remux) {
				// AC-3 remux takes priority
				type = "A_AC3";
			} else {
				if ( pcm || this instanceof TsMuxeRAudio)
				{
					type = "A_LPCM";
				}
				if ( dtsRemux || this instanceof TsMuxeRAudio)
				{
					type = "A_LPCM";
					if (params.mediaRenderer.isMuxDTSToMpeg()) {
						type = "A_DTS";
					}
				}
			}
			if (params.aid != null && params.aid.getAudioProperties().getAudioDelay() != 0 && params.timeseek == 0) {
				timeshift = "timeshift=" + params.aid.getAudioProperties().getAudioDelay() + "ms, ";
			}
			pw.println(type + ", \"" + ffAudioPipe[0].getOutputPipe() + "\", " + timeshift + "track=2");
		} else if (ffAudioPipe != null) {
			for (int i = 0; i < media.getAudioTracksList().size(); i++) {
				DLNAMediaAudio lang = media.getAudioTracksList().get(i);
				String timeshift = "";
				boolean ac3Remux;
				boolean dtsRemux;
				boolean pcm;
				/*
				 Disable AC-3 remux for stereo tracks with 384 kbits bitrate and PS3 renderer (PS3 FW bug?)
			  	 TODO check new firmwares
			  	 Commented out until we can find a way to detect when a video has an audio track that switches from 2 to 6 channels
			  	 because MEncoder can't handle those files, which are very common these days.
			 	*/
				// final boolean ps3_and_stereo_and_384_kbits = (params.mediaRenderer.isPS3() && lang.getAudioProperties().getNumberOfChannels() == 2)
				//	&& (lang.getBitRate() > 370000 && lang.getBitRate() < 400000);
				final boolean ps3_and_stereo_and_384_kbits = false;
				ac3Remux = lang.isAC3() && !ps3_and_stereo_and_384_kbits && configuration.isAudioRemuxAC3();
				dtsRemux = configuration.isAudioEmbedDtsInPcm() && lang.isDTS() && params.mediaRenderer.isDTSPlayable();
				pcm = configuration.isAudioUsePCM() &&
					!mp4_with_non_h264 &&
					(
						lang.isLossless() ||
						(lang.isDTS() && lang.getAudioProperties().getNumberOfChannels() <= 6) ||
						lang.isTrueHD() ||
						(
							!configuration.isMencoderUsePcmForHQAudioOnly() &&
							(
								params.aid.isAC3() ||
								params.aid.isMP3() ||
								params.aid.isAAC() ||
								params.aid.isVorbis() ||
								// params.aid.isWMA() ||
								params.aid.isMpegAudio()
							)
						)
					) && params.mediaRenderer.isLPCMPlayable();
				String type = "A_AC3";
				if (ac3Remux) {
					// AC-3 remux takes priority
					type = "A_AC3";
				} else {
					if (pcm)
					{
						type = "A_LPCM";
					}
					if (dtsRemux)
					{
						type = "A_LPCM";
						if (params.mediaRenderer.isMuxDTSToMpeg()) {
							type = "A_DTS";
						}
					}
				}
				if (lang.getAudioProperties().getAudioDelay() != 0 && params.timeseek == 0) {
					timeshift = "timeshift=" + lang.getAudioProperties().getAudioDelay() + "ms, ";
				}
				pw.println(type + ", \"" + ffAudioPipe[i].getOutputPipe() + "\", " + timeshift + "track=" + (2 + i));
			}
		}

		pw.close();

		PipeProcess tsPipe = new PipeProcess(System.currentTimeMillis() + "tsmuxerout.ts");
		String[] cmdArray = new String[]{
			executable(),
			f.getAbsolutePath(),
			tsPipe.getInputPipe()
		};

		cmdArray = finalizeTranscoderArgs(
			filename,
			dlna,
			media,
			params,
			cmdArray
		);

		ProcessWrapperImpl p = new ProcessWrapperImpl(cmdArray, params);
		params.maxBufferSize = 100;
		params.input_pipes[0] = tsPipe;
		params.stdin = null;
		ProcessWrapper pipe_process = tsPipe.getPipeProcess();
		p.attachProcess(pipe_process);
		pipe_process.runInNewThread();

		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
		}
		tsPipe.deleteLater();

		ProcessWrapper ff_pipe_process = ffVideoPipe.getPipeProcess();
		p.attachProcess(ff_pipe_process);
		ff_pipe_process.runInNewThread();
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
		}
		ffVideoPipe.deleteLater();

		p.attachProcess(ffVideo);
		ffVideo.runInNewThread();
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
		}

		if (ffAudioPipe != null && params.aid != null) {
			for (int i = 0; i < ffAudioPipe.length; i++) {
				ff_pipe_process = ffAudioPipe[i].getPipeProcess();
				p.attachProcess(ff_pipe_process);
				ff_pipe_process.runInNewThread();
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
				}
				ffAudioPipe[i].deleteLater();
				p.attachProcess(ffAudio[i]);
				ffAudio[i].runInNewThread();
			}
		}

		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
		}

		p.runInNewThread();
		return p;
	}

	/**
	 * Write the resource "/resources/images/fake.jpg" to a physical file on disk.
	 *
	 * @return The filename of the file on disk.
	 */
	private String writeResourceToFile(String resourceName) {
		String outputFileName = resourceName.substring(resourceName.lastIndexOf("/") + 1);

		try {
			outputFileName = configuration.getTempFolder() + "/" + outputFileName;
		} catch (IOException e) {
			logger.warn("Failure to determine temporary folder.", e);
		}

		File outputFile = new File(outputFileName);

		// Copy the resource file only once
		if (!outputFile.exists()) {
			final URL resourceUrl = getClass().getClassLoader().getResource(resourceName);
			byte[] buffer = new byte[1024];
			int byteCount;

			InputStream inputStream = null;
			OutputStream outputStream = null;

			try {
				inputStream = resourceUrl.openStream();
				outputStream = new FileOutputStream(outputFileName);

				while ((byteCount = inputStream.read(buffer)) >= 0) {
					outputStream.write(buffer, 0, byteCount);
				}
			} catch (final IOException e) {
				logger.error("Failure on saving the embedded resource " + resourceName + " to the file " + outputFile.getAbsolutePath(), e);
			} finally {
				if (inputStream != null) {
					try {
						inputStream.close();
					} catch (final IOException e) {
						logger.warn("Problem closing an input stream while reading data from the embedded resource " + resourceName, e);
					}
				}

				if (outputStream != null) {
					try {
						outputStream.flush();
						outputStream.close();
					} catch (final IOException e) {
						logger.warn("Problem closing the output stream while writing the file " + outputFile.getAbsolutePath(), e);
					}
				}
			}
		}

		return outputFileName;
	}

	@Override
	public String mimeType() {
		return "video/mpeg";
	}

	@Override
	public String name() {
		return "tsMuxeR";
	}

	@Override
	public int type() {
		return Format.VIDEO;
	}
	private JCheckBox tsmuxerforcefps;
	private JCheckBox muxallaudiotracks;

	@Override
	public JComponent config() {
		// Apply the orientation for the locale
		Locale locale = new Locale(configuration.getLanguage());
		ComponentOrientation orientation = ComponentOrientation.getOrientation(locale);
		String colSpec = FormLayoutUtil.getColSpec(COL_SPEC, orientation);
		FormLayout layout = new FormLayout(colSpec, ROW_SPEC);

		PanelBuilder builder = new PanelBuilder(layout);

		CellConstraints cc = new CellConstraints();


		JComponent cmp = builder.addSeparator(Messages.getString("TSMuxerVideo.3"), FormLayoutUtil.flip(cc.xyw(2, 1, 1), colSpec, orientation));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		tsmuxerforcefps = new JCheckBox(Messages.getString("TSMuxerVideo.2"));
		tsmuxerforcefps.setContentAreaFilled(false);
		if (configuration.isTsmuxerForceFps()) {
			tsmuxerforcefps.setSelected(true);
		}
		tsmuxerforcefps.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				configuration.setTsmuxerForceFps(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(tsmuxerforcefps, FormLayoutUtil.flip(cc.xy(2, 3), colSpec, orientation));

		muxallaudiotracks = new JCheckBox(Messages.getString("TSMuxerVideo.19"));
		muxallaudiotracks.setContentAreaFilled(false);
		if (configuration.isMuxAllAudioTracks()) {
			muxallaudiotracks.setSelected(true);
		}

		muxallaudiotracks.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				configuration.setMuxAllAudioTracks(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(muxallaudiotracks, FormLayoutUtil.flip(cc.xy(2, 5), colSpec, orientation));

		JPanel panel = builder.getPanel();

		// Apply the orientation to the panel and all components in it
		panel.applyComponentOrientation(orientation);

		return panel;
	}

	@Override
	public boolean isInternalSubtitlesSupported() {
		return false;
	}

	@Override
	public boolean isExternalSubtitlesSupported() {
		return false;
	}

	@Override
	public boolean isPlayerCompatible(RendererConfiguration mediaRenderer) {
		return mediaRenderer != null && mediaRenderer.isMuxH264MpegTS();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isCompatible(DLNAResource dlna) {
		if (!(
			PlayerUtil.isVideo(dlna, Format.Identifier.MKV) ||
			PlayerUtil.isVideo(dlna, Format.Identifier.MPG)
		)) {
			return false;
		}

		/*
		 * Notes:
		 *
		 * 1) isCompatible is used for two separate tasks: 1) selection
		 * of #--TRANSCODE--# folder entries (PlayerFactory.getPlayers)
		 * and 2) assignment of the default player for a resource
		 * (PlayerFactory.getPlayer)
		 *
		 * 2) for resources that aren't in the #--TRANSCODE--# folder, it's understood
		 * that tsMuxeR will only stream the first audio track and that it won't/can't
		 * stream subtitles, so no need to check either
		 */
		if (dlna.isNoName()) { // #--TRANSCODE--# folder entry
			DLNAMediaAudio dlnaMediaAudio = dlna.getMediaAudio();

			// media is non-null and audioTrackList is non-empty by definition if
			// mediaAudio has been set (see FileTranscodeVirtualFolder)
			if ((dlnaMediaAudio != null) && !dlnaMediaAudio.toString().equals(dlna.getMedia().getFirstAudioTrack().toString())) {
				// PMS only supports playback of the first audio track for tsMuxeR
				return false;
			}

			// XXX why is tsMuxeR compatible if the subtitle language is null?
			if ((dlna.getMediaSubtitle() != null) && (dlna.getMediaSubtitle().getLang() != null)) {
				return false;
			}
		}

		return true;
	}
}
