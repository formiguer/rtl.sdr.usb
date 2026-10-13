#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include "libusb.h"

static uint8_t bitrev(uint8_t b){ uint8_t r=0; for(int i=0;i<8;i++) if(b&(1<<i)) r|=1<<(7-i); return r; }
/* estado deseado del R820T2 tras bit-reverse: [0]=chip id, [2]=PLL bloqueado, [4]=vco_fine_tune=1 y cal code=5 */
static const uint8_t desired[8] = {0x96, 0x00, 0x50, 0x00, 0x15, 0x00, 0x00, 0x00};
static int dummy_a, dummy_b;

int libusb_init(libusb_context **c){ *c=(libusb_context*)&dummy_a; return 0; }
void libusb_exit(libusb_context *c){ (void)c; }
long libusb_get_device_list(libusb_context *c, libusb_device ***l){ (void)c; static libusb_device *arr[2]; arr[0]=(libusb_device*)&dummy_b; arr[1]=0; *l=arr; return 1; }
void libusb_free_device_list(libusb_device **l,int u){ (void)l;(void)u; }
int libusb_get_device_descriptor(libusb_device *d, struct libusb_device_descriptor *dd){ (void)d; memset(dd,0,sizeof *dd); dd->idVendor=0x0bda; dd->idProduct=0x2838; dd->iManufacturer=1; dd->iProduct=2; dd->iSerialNumber=3; return 0; }
int libusb_open(libusb_device *d, libusb_device_handle **h){ (void)d; *h=(libusb_device_handle*)&dummy_a; return 0; }
void libusb_close(libusb_device_handle *h){ (void)h; }
libusb_device *libusb_get_device(libusb_device_handle *h){ (void)h; return (libusb_device*)&dummy_b; }
int libusb_kernel_driver_active(libusb_device_handle *h,int i){ (void)h;(void)i; return 0; }
int libusb_detach_kernel_driver(libusb_device_handle *h,int i){ (void)h;(void)i; return 0; }
int libusb_attach_kernel_driver(libusb_device_handle *h,int i){ (void)h;(void)i; return 0; }
int libusb_claim_interface(libusb_device_handle *h,int i){ (void)h;(void)i; return 0; }
int libusb_release_interface(libusb_device_handle *h,int i){ (void)h;(void)i; return 0; }
int libusb_reset_device(libusb_device_handle *h){ (void)h; return 0; }
int libusb_get_string_descriptor_ascii(libusb_device_handle *h, uint8_t idx, unsigned char *data, int length){ (void)h;(void)idx; strncpy((char*)data,"Generic",length); return 7; }

int libusb_control_transfer(libusb_device_handle *h, uint8_t rt, uint8_t req, uint16_t value, uint16_t index, unsigned char *data, uint16_t len, unsigned int timeout){
  (void)h;(void)timeout;
  if (rt & 0x80) { /* IN */
    memset(data, 0, len);
    if ((index >> 8) == 6 && value == 0x34) { /* I2C del R820T */
      for (int i=0;i<len && i<8;i++) data[i] = bitrev(desired[i]);
    }
    printf("IN  rt=%02x req=%d val=%04x idx=%04x len=%d ->", rt, req, value, index, len);
    for (int i=0;i<len;i++) printf(" %02x", data[i]);
    printf("\n");
  } else {
    printf("OUT rt=%02x req=%d val=%04x idx=%04x len=%d <-", rt, req, value, index, len);
    for (int i=0;i<len;i++) printf(" %02x", data[i]);
    printf("\n");
  }
  return len;
}
int libusb_bulk_transfer(libusb_device_handle *h, unsigned char ep, unsigned char *d, int l, int *t, unsigned int to){ (void)h;(void)ep;(void)d;(void)l;(void)to; *t=0; return 0; }
struct libusb_transfer *libusb_alloc_transfer(int n){ (void)n; return calloc(1,sizeof(struct libusb_transfer)); }
void libusb_free_transfer(struct libusb_transfer *t){ free(t); }
int libusb_submit_transfer(struct libusb_transfer *t){ (void)t; return 0; }
int libusb_cancel_transfer(struct libusb_transfer *t){ (void)t; return 0; }
int libusb_handle_events_timeout_completed(libusb_context *c, struct timeval *tv, int *x){ (void)c;(void)tv;(void)x; return 0; }
unsigned char *libusb_dev_mem_alloc(libusb_device_handle *h, size_t n){ (void)h;(void)n; return 0; }
int libusb_dev_mem_free(libusb_device_handle *h, unsigned char *b, size_t n){ (void)h;(void)b;(void)n; return 0; }
