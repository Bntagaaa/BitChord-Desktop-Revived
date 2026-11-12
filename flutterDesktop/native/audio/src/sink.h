#pragma once
#include <cstdint>
#include <memory>
#include <string>
namespace bc {
// Dedicated push owner, never an OS callback. Writes return whole accepted frames.
class Sink {
public:
 uint32_t rate=0, channels=0, backend=0, timing=0;
 uint64_t submitted=0;
 virtual ~Sink()=default;
 virtual uint32_t write(const float*, uint32_t)=0;
 virtual uint64_t played()=0;
 virtual void pause(bool)=0;
 virtual void flush()=0;
};
std::unique_ptr<Sink> open_sink(uint32_t rate,uint32_t channels,const std::string& endpoint);
}
