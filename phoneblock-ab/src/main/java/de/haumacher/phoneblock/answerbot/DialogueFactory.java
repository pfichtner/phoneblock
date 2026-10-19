/*
 * Copyright (c) 2023 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.phoneblock.answerbot;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.mjsip.media.AudioStreamer;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.media.RtpStreamReceiver;
import org.mjsip.media.RtpStreamReceiverListener;
import org.mjsip.media.RtpStreamReceiverListenerAdapter;
import org.mjsip.media.RtpStreamSender;
import org.mjsip.media.RtpStreamSenderListener;
import org.mjsip.media.StreamerOptions;
import org.mjsip.media.rx.AudioReceiver;
import org.mjsip.media.rx.AudioRxHandle;
import org.mjsip.media.rx.RtpAudioRxHandler;
import org.mjsip.media.rx.RtpReceiverOptions;
import org.mjsip.media.tx.AudioTXHandle;
import org.mjsip.media.tx.AudioTransmitter;
import org.mjsip.media.tx.RtpAudioTxHandle;
import org.mjsip.media.tx.RtpSenderOptions;
import org.mjsip.rtp.RtpControl;
import org.mjsip.rtp.RtpPayloadFormat;
import org.mjsip.sound.AudioFile;
import org.mjsip.ua.sound.AlawSilenceTrimmer;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.LoggerFactory;
import org.zoolu.net.UdpSocket;
import org.zoolu.sound.CodecType;
import org.zoolu.sound.SimpleAudioSystem;
import org.zoolu.util.Encoder;

/**
 * {@link StreamerFactory} creating a bot correspondence.
 */
public final class DialogueFactory implements StreamerFactory {
	
	static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DialogueFactory.class);
	
	private final Map<AudioType, Map<SpeechType, List<File>>> _audioFragmentsByType;
	private final String _recordingFile;
	private DialogOptions _config;

	/** 
	 * Creates a {@link DialogueFactory}.
	 */
	public DialogueFactory(DialogOptions config, Map<AudioType, Map<SpeechType, List<File>>> audioFragmentsByType, String recordingFile) {
		_config = config;
		_audioFragmentsByType = audioFragmentsByType;
		_recordingFile = recordingFile;
	}

	@Override
	public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flow_spec) {
		AudioType matchedType = null;
		MediaSpec spec = flow_spec.getMediaSpec();
		for (AudioType type : _audioFragmentsByType.keySet()) {
			if (type.matches(spec)) {
				matchedType = type;
				break;
			}
		}
		
		if (matchedType == null) {
			LOG.warn("No compatible audio found.");
			return null;
		}
		
		Map<SpeechType, List<File>> dialogue = _audioFragmentsByType.get(matchedType);
		if (dialogue == null) {
			LOG.warn("No audio data found for audio format: " + matchedType);
			return null;
		}
		LOG.info("Starting dialogue with audio type: " + matchedType);
		
		SpeechDispatcher speechDispatcher = new SpeechDispatcher(dialogue);

		int sampleRate = flow_spec.getMediaSpec().getSampleRate();
		try {
			OutputStream recording = _recordingFile != null ?
					AudioFile.getAudioFileOutputStream(_recordingFile, SimpleAudioSystem.getAudioFormat(flow_spec.getMediaSpec().getCodecType(), sampleRate))
					: OutputStream.nullOutputStream();

			// Both directions are written to the recording, therefore writes are serialized.
			// shortcut: the two directions are appended in arrival order, not sample-mixed; only
			// moments where both talk at once interleave. Upgrade to a real mixer if that matters.
			OutputStream mixed = new SynchronizedOutputStream(recording);

			// Trims silence from the counterpart's (incoming) audio and forwards it to the recording.
			AlawSilenceTrimmer silenceTrimmer =
				new AlawSilenceTrimmer(sampleRate, _config.bufferTime(), _config.minSilenceTime(), _config.paddingTime(), _config.silenceDb(), mixed, speechDispatcher);

			// Trims silence from the bot's own (outgoing) audio and forwards it to the recording.
			AlawSilenceTrimmer ownVoiceTrimmer = _recordingFile != null ?
				new AlawSilenceTrimmer(sampleRate, _config.bufferTime(), _config.minSilenceTime(), _config.paddingTime(), _config.silenceDb(), mixed, null)
				: null;

			// Tap the bot's outgoing audio, so that it ends up in the recording as well.
			InputStream ownVoice = ownVoiceTrimmer == null ? speechDispatcher : new InputStream() {
				@Override
				public int read() throws IOException {
					int audio = speechDispatcher.read();
					if (audio >= 0) {
						ownVoiceTrimmer.write(audio);
					}
					return audio;
				}
			};

			AudioTransmitter tx = new AudioTransmitter() {
				@Override
				public AudioTXHandle createSender(RtpSenderOptions options, UdpSocket udp_socket, AudioFormat audio_format,
						CodecType codec, int payload_type, RtpPayloadFormat payloadFormat, int sample_rate, int channels,
						Encoder additional_encoder, long packet_time, int packet_size, String remote_addr, int remote_port,
						RtpStreamSenderListener listener, RtpControl rtpControl) throws IOException {
					
					RtpStreamSender sender = new RtpStreamSender(options, ownVoice, true, payload_type, payloadFormat, sample_rate,
							channels, packet_time, packet_size, additional_encoder, udp_socket, remote_addr, remote_port, rtpControl, listener);
					return new RtpAudioTxHandle(sender);
				}
			};
			
			AudioReceiver rx;

			rx = new AudioReceiver() {
				@Override
				public AudioRxHandle createReceiver(RtpReceiverOptions options, UdpSocket socket,
						AudioFormat audio_format, CodecType codec, int payload_type, RtpPayloadFormat payloadFormat,
						int sample_rate, int channels, Encoder additional_decoder,
						RtpStreamReceiverListener listener) throws IOException, UnsupportedAudioFileException {

					RtpStreamReceiverListener onTerminate = new RtpStreamReceiverListenerAdapter() {
						@Override
						public void onRtpStreamReceiverTerminated(RtpStreamReceiver rr, Exception error) {
							try {
								if (ownVoiceTrimmer != null) {
									ownVoiceTrimmer.close();
								}
								silenceTrimmer.close();
								recording.close();
							} catch (IOException ex) {
								AnswerBot.LOG.error("Failed to close recording.", ex);
							}
						}
					}.andThen(listener);

					return new RtpAudioRxHandler(new RtpStreamReceiver(options, silenceTrimmer, additional_decoder, payloadFormat, socket, onTerminate));
				}
			};

			StreamerOptions options = StreamerOptions.builder().build();
			return new AudioStreamer(executor, flow_spec, tx, rx, options);
		} catch (IOException ex) {
			throw new IOError(ex);
		}
	}

	/**
	 * {@link OutputStream} that serializes writes, so that audio of both directions can be recorded concurrently.
	 */
	private static final class SynchronizedOutputStream extends OutputStream {

		private final OutputStream _out;

		SynchronizedOutputStream(OutputStream out) {
			_out = out;
		}

		@Override
		public synchronized void write(int b) throws IOException {
			_out.write(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			_out.write(b, off, len);
		}

		@Override
		public synchronized void close() throws IOException {
			_out.close();
		}
	}
}