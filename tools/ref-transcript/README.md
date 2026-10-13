# Transcripción de referencia del driver

`adsb/src/test/resources/librtlsdr_r820t_transcript.txt` es la secuencia exacta de transferencias USB que
hace el `librtlsdr` oficial (osmocom/rtl-sdr) con un RTL2832U + R820T2 simulado, para el mismo guion que
`DriverTranscriptTest`. El test comprueba que el driver en Kotlin genera **la misma secuencia byte a byte**.

Para regenerarla (hace falta gcc y un clon de https://github.com/osmocom/rtl-sdr en `$RTLSDR`):

```sh
gcc -w -I stub -I $RTLSDR/include -o ref ref.c stub/fake.c \
    $RTLSDR/src/librtlsdr.c $RTLSDR/src/tuner_*.c -lm
./ref > ../../adsb/src/test/resources/librtlsdr_r820t_transcript.txt
```
