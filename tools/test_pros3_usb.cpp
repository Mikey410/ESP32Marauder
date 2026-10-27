#include "../esp32_marauder/UsbCommandLine.h"
#include <cassert>
#include <cstring>
#include <string>
int main() {
  UsbCommandLine line;
  for (char c : std::string("scan")) assert(!line.push(c));
  for (char c : std::string("all\r")) assert(!line.push(c));
  assert(line.push('\n')); assert(std::strcmp(line.data(), "scanall") == 0);
  for (char c : std::string("stopscan")) assert(!line.push(c));
  assert(line.push('\n')); assert(std::strcmp(line.data(), "stopscan") == 0);
  for (int i=0; i<512; ++i) assert(!line.push('a'));
  assert(line.push('\n')); assert(std::strlen(line.data()) == 512);
  for (int i=0; i<513; ++i) assert(!line.push('a'));
  assert(!line.push('\n'));
  assert(!line.push('x')); assert(line.push('\n')); assert(std::strcmp(line.data(), "x") == 0);
  assert(!line.push('\0')); assert(!line.push('x')); assert(!line.push('\n'));
  assert(line.push('\n')); assert(std::strcmp(line.data(), "") == 0);
}
