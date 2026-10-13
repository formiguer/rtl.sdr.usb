/* libusb simulado: registra las transferencias de control y responde como un RTL-SDR con R820T2 */
#ifndef FAKE_LIBUSB_H
#define FAKE_LIBUSB_H
#include <stdint.h>
#define LIBUSB_API_VERSION 0x01000100
#define LIBUSB_CALL
typedef struct libusb_context libusb_context;
typedef struct libusb_device libusb_device;
typedef struct libusb_device_handle libusb_device_handle;
struct libusb_device_descriptor { uint16_t idVendor, idProduct; uint8_t iManufacturer, iProduct, iSerialNumber; };
enum { LIBUSB_ENDPOINT_IN = 0x80, LIBUSB_ENDPOINT_OUT = 0x00, LIBUSB_REQUEST_TYPE_VENDOR = 0x40 };
enum { LIBUSB_ERROR_ACCESS = -3, LIBUSB_ERROR_INTERRUPTED = -10 };
enum { LIBUSB_TRANSFER_COMPLETED, LIBUSB_TRANSFER_ERROR, LIBUSB_TRANSFER_TIMED_OUT, LIBUSB_TRANSFER_CANCELLED, LIBUSB_TRANSFER_STALL, LIBUSB_TRANSFER_NO_DEVICE, LIBUSB_TRANSFER_OVERFLOW };
struct libusb_transfer; typedef void (*libusb_transfer_cb_fn)(struct libusb_transfer *);
struct libusb_transfer { int status; int actual_length; unsigned char *buffer; void *user_data; };
int libusb_init(libusb_context **ctx);
void libusb_exit(libusb_context *ctx);
long libusb_get_device_list(libusb_context *ctx, libusb_device ***list);
void libusb_free_device_list(libusb_device **list, int unref);
int libusb_get_device_descriptor(libusb_device *dev, struct libusb_device_descriptor *d);
int libusb_open(libusb_device *dev, libusb_device_handle **h);
void libusb_close(libusb_device_handle *h);
libusb_device *libusb_get_device(libusb_device_handle *h);
int libusb_kernel_driver_active(libusb_device_handle *h, int i);
int libusb_detach_kernel_driver(libusb_device_handle *h, int i);
int libusb_attach_kernel_driver(libusb_device_handle *h, int i);
int libusb_claim_interface(libusb_device_handle *h, int i);
int libusb_release_interface(libusb_device_handle *h, int i);
int libusb_reset_device(libusb_device_handle *h);
int libusb_get_string_descriptor_ascii(libusb_device_handle *h, uint8_t idx, unsigned char *data, int length);
int libusb_control_transfer(libusb_device_handle *h, uint8_t rt, uint8_t req, uint16_t value, uint16_t index, unsigned char *data, uint16_t len, unsigned int timeout);
int libusb_bulk_transfer(libusb_device_handle *h, unsigned char ep, unsigned char *data, int length, int *transferred, unsigned int timeout);
struct libusb_transfer *libusb_alloc_transfer(int n);
void libusb_free_transfer(struct libusb_transfer *t);
int libusb_submit_transfer(struct libusb_transfer *t);
int libusb_cancel_transfer(struct libusb_transfer *t);
int libusb_handle_events_timeout_completed(libusb_context *c, struct timeval *tv, int *completed);
unsigned char *libusb_dev_mem_alloc(libusb_device_handle *h, size_t n);
int libusb_dev_mem_free(libusb_device_handle *h, unsigned char *b, size_t n);
static inline void libusb_fill_bulk_transfer(struct libusb_transfer *t, libusb_device_handle *h, unsigned char ep, unsigned char *buf, int len, libusb_transfer_cb_fn cb, void *ud, unsigned int to) { (void)t;(void)h;(void)ep;(void)buf;(void)len;(void)cb;(void)ud;(void)to; }
#endif
