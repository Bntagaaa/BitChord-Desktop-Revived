#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <stdexcept>
namespace bc {
class PauseUnsupported : public std::runtime_error { public: using std::runtime_error::runtime_error; };
class DeviceUnavailable : public std::runtime_error { public: using std::runtime_error::runtime_error; };
// Dedicated push owner, never an OS callback. Writes return whole accepted frames.
class Sink {
public:
 uint32_t rate=0, channels=0, backend=0, timing=0, bits=32;
 uint64_t submitted=0;
 virtual ~Sink()=default;
 virtual uint32_t write(const float*, uint32_t)=0;
 virtual uint64_t played()=0;
 virtual bool drained() { return played()>=submitted; }
 virtual void pause(bool)=0;
 virtual void flush()=0;
};
std::unique_ptr<Sink> open_sink(uint32_t rate,uint32_t channels,const std::string& endpoint);
}
