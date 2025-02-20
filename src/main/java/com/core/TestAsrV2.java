package com.core;

import com.google.api.gax.rpc.BidiStream;
import com.google.cloud.speech.v2.ExplicitDecodingConfig;
import com.google.cloud.speech.v2.RecognitionConfig;
import com.google.cloud.speech.v2.RecognizerName;
import com.google.cloud.speech.v2.SpeechClient;
import com.google.cloud.speech.v2.SpeechRecognitionAlternative;
import com.google.cloud.speech.v2.SpeechSettings;
import com.google.cloud.speech.v2.StreamingRecognitionConfig;
import com.google.cloud.speech.v2.StreamingRecognitionResult;
import com.google.cloud.speech.v2.StreamingRecognizeRequest;
import com.google.cloud.speech.v2.StreamingRecognizeResponse;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.DataLine.Info;
import javax.sound.sampled.TargetDataLine;


public class TestAsrV2 {

  static final String projectID = "REPLACE_YOUR_PROJECT_ID";
  static final String location = "us-central1";
  static final String language = "en-US"; //es-ES,cmm-Hans-CN,en-US,ja-JP
  static final String target_language = "zh-CN";

  /** Run speech recognition tasks. */
  public static void main(String... args) throws Exception {
    streamingMicRecognize(projectID,location,language);
  }

  public static void streamingMicRecognize(String projectID, String location, String language) throws Exception {
    ExplicitDecodingConfig explicitDecodingConfig = ExplicitDecodingConfig.newBuilder()
        .setEncodingValue(ExplicitDecodingConfig.AudioEncoding.LINEAR16_VALUE)
        .setSampleRateHertz(8000)
        .setAudioChannelCount(1)
        .build();
    RecognitionConfig recognitionConfig =
        RecognitionConfig.newBuilder()
            .setModel("chirp_2")
            .addLanguageCodes(language)
            .setExplicitDecodingConfig(explicitDecodingConfig)
            .build();
    //暂时不支持setInterimResults
    StreamingRecognitionConfig streamingRecognitionConfig =
        StreamingRecognitionConfig.newBuilder()
            .setConfig(recognitionConfig)
            //  .setStreamingFeatures(StreamingRecognitionFeatures.newBuilder().setInterimResults(false).build())
            .build();

    SpeechSettings speechSettings = SpeechSettings.newBuilder()
        .setEndpoint(location + "-speech.googleapis.com:443")
        .build();

    try (SpeechClient client = SpeechClient.create(speechSettings)) {
      BidiStream<StreamingRecognizeRequest, StreamingRecognizeResponse> bidiStream =   client.streamingRecognizeCallable().call();
      CompletableFuture.runAsync(() -> {
        try {
          StreamingRecognizeRequest request =
              StreamingRecognizeRequest.newBuilder()
                  .setRecognizer(String.valueOf(RecognizerName.of(projectID,location,"_")))
                  .setStreamingConfig(streamingRecognitionConfig)
                  .build();
          bidiStream.send(request);

                     /*
                         microphone audio
                         SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true,
                         bigEndian: false
                         And Target data line captures the audio stream the microphone produces.
                     */
          AudioFormat audioFormat = new AudioFormat(8000, 16, 1, true, false);
          DataLine.Info targetInfo =
              new Info(
                  TargetDataLine.class,
                  audioFormat);
          if (!AudioSystem.isLineSupported(targetInfo)) {
            System.out.println("Microphone not supported");
            System.exit(0);
          }
          TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
          targetDataLine.open(audioFormat);
          targetDataLine.start();
          AudioInputStream audio = new AudioInputStream(targetDataLine);

          System.out.println("Start speaking");
          long startTime = System.currentTimeMillis();
          while (true) {
            long estimatedTime = System.currentTimeMillis() - startTime;
            byte[] data = new byte[6400];
            audio.read(data);
            if (estimatedTime > 240000) { // 240 seconds
              System.out.println("Stop speaking.");
              targetDataLine.stop();
              targetDataLine.close();
              break;
            }
            bidiStream.send(StreamingRecognizeRequest.newBuilder()
                .setAudio(ByteString.copyFrom(data))
                .build());
          }
          bidiStream.closeSend();
        }catch (Exception e){
          System.out.println(e);
        }
      });


      CompletableFuture.runAsync(() -> {
        try {
          int i =0;
          for (StreamingRecognizeResponse response : bidiStream) {
            if(response.isInitialized()){
              System.out.println("Start Receive asr result " + i++ + " at:" + LocalDateTime.now());
              StreamingRecognitionResult result = response.getResultsList().get(0);
              SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
              System.out.printf("Transcript : %s\n", alternative.getTranscript());
              System.out.printf("Is Final : %s\n", result.getIsFinal());
              System.out.printf("Translation : %s\n", translate(projectID, location, alternative.getTranscript()));
            }
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }).get(); // Wait for the responses to be processed
    } catch (Exception e) {
      System.out.println(e.getMessage());
    }
  }

  public static String translate (String projectId, String location, String content) throws IOException {

    try (TranslationServiceClient translationServiceClient = TranslationServiceClient.create()) {
      LocationName parent = LocationName.of(projectId, location);
      TranslateTextResponse response =
          translationServiceClient.translateText(parent, target_language, Arrays.asList(content));
      return response.getTranslationsList().get(0).getTranslatedText();
    }
  }
}

