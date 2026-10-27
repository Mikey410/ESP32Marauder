#pragma once
#include <stddef.h>

// Discard an entire overlong line, never execute a truncated command.
class UsbCommandLine {
 public:
  bool push(char ch) {
    if (ready_) { size_ = 0; ready_ = false; }
    if (ch == '\r') return false;
    if (ch == '\n') {
      if (overflow_) { overflow_ = false; size_ = 0; return false; }
      buffer_[size_] = 0;
      ready_ = true;
      return true;
    }
    if (overflow_) return false;
    if (ch == 0 || size_ == sizeof(buffer_) - 1) {
      overflow_ = true;
      return false;
    }
    buffer_[size_++] = ch;
    return false;
  }
  const char* data() const { return buffer_; }
 private:
  char buffer_[513] = {};
  size_t size_ = 0;
  bool overflow_ = false;
  bool ready_ = false;
};
