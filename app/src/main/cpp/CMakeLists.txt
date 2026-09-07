# EMRS-Android 自研串口驱动（架构 §3.2）
# 仅编译自研 serial_port.c，产物 libemrsserial.so
cmake_minimum_required(VERSION 3.18.1)
project("emrsserial" C)

add_library(emrsserial SHARED serial/serial_port.c)

target_include_directories(emrsserial PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/serial)

find_library(log-lib log)
target_link_libraries(emrsserial ${log-lib})
