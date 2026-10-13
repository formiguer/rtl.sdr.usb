#include <stdio.h>
#include <stdint.h>
#include "rtl-sdr.h"
int main(void){
  rtlsdr_dev_t *dev;
  if (rtlsdr_open(&dev, 0) < 0) { fprintf(stderr,"open failed\n"); return 1; }
  printf("--- tuner=%d\n", rtlsdr_get_tuner_type(dev));
  printf("--- set_sample_rate 2000000\n");  rtlsdr_set_sample_rate(dev, 2000000);
  printf("--- gain mode manual\n");        rtlsdr_set_tuner_gain_mode(dev, 1);
  printf("--- gain 496\n");                rtlsdr_set_tuner_gain(dev, 496);
  printf("--- agc off\n");                 rtlsdr_set_agc_mode(dev, 0);
  printf("--- center freq 1090000000\n");  rtlsdr_set_center_freq(dev, 1090000000);
  printf("--- reset buffer\n");            rtlsdr_reset_buffer(dev);
  printf("--- airband: rate 1200000\n");   rtlsdr_set_sample_rate(dev, 1200000);
  printf("--- gain 402\n");                rtlsdr_set_tuner_gain(dev, 402);
  printf("--- freq 118300000\n");          rtlsdr_set_center_freq(dev, 118300000);
  printf("--- freq 131725000\n");          rtlsdr_set_center_freq(dev, 131725000);
  printf("--- freq 136450000\n");          rtlsdr_set_center_freq(dev, 136450000);
  printf("--- gain 0\n");                  rtlsdr_set_tuner_gain(dev, 0);
  printf("--- gain mode auto\n");          rtlsdr_set_tuner_gain_mode(dev, 0);
  printf("--- close\n");                   rtlsdr_close(dev);
  return 0;
}
