#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include "libretro.h"

static void logger(enum retro_log_level level, const char *fmt, ...) {}
static bool environment(unsigned command, void *data) {
    switch(command) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback*)data)->log = logger; return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char**)data = "/data/local/tmp/nes-cheat-probe"; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
        ((struct retro_variable*)data)->value = NULL; return false;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool*)data = false; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        return true;
    default: return false;
    }
}
static void video(const void *data,unsigned w,unsigned h,size_t pitch) {}
static void audio(int16_t left,int16_t right) {}
static size_t audio_batch(const int16_t *data,size_t frames) { return frames; }
static void poll(void) {}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id) { return 0; }
static void check(int ok,const char *message) {
    if(!ok) { fprintf(stderr,"FAIL %s\n",message); exit(2); }
    printf("PASS %s\n",message);
}

int main(int argc,char **argv) {
    check(argc == 2,"library argument");
    void *handle = dlopen(argv[1],RTLD_NOW);
    if(!handle) { fprintf(stderr,"%s\n",dlerror()); return 2; }
    void (*set_env)(retro_environment_t) = dlsym(handle,"retro_set_environment");
    void (*set_video)(retro_video_refresh_t) = dlsym(handle,"retro_set_video_refresh");
    void (*set_audio)(retro_audio_sample_t) = dlsym(handle,"retro_set_audio_sample");
    void (*set_batch)(retro_audio_sample_batch_t) = dlsym(handle,"retro_set_audio_sample_batch");
    void (*set_poll)(retro_input_poll_t) = dlsym(handle,"retro_set_input_poll");
    void (*set_input)(retro_input_state_t) = dlsym(handle,"retro_set_input_state");
    void (*init)(void) = dlsym(handle,"retro_init");
    bool (*load)(const struct retro_game_info*) = dlsym(handle,"retro_load_game");
    void (*run)(void) = dlsym(handle,"retro_run");
    void *(*memory)(unsigned) = dlsym(handle,"retro_get_memory_data");
    void (*cheat)(unsigned,bool,const char*) = dlsym(handle,"retro_cheat_set");
    void (*reset)(void) = dlsym(handle,"retro_cheat_reset");
    void (*unload)(void) = dlsym(handle,"retro_unload_game");
    void (*deinit)(void) = dlsym(handle,"retro_deinit");
    set_env(environment); set_video(video); set_audio(audio); set_batch(audio_batch); set_poll(poll); set_input(input);
    init();
    unsigned char rom[16+16384+8192] = { 'N','E','S',26,1,1 };
    const unsigned char program[] = { 0x78,0xD8,0xA9,0x11,0x85,0x00,0x8D,0x00,0x02,
        0xAD,0x00,0x02,0x8D,0x01,0x02,0xAD,0x00,0x80,0x8D,0x02,0x02,0x4C,0x09,0x80 };
    memcpy(rom+16,program,sizeof(program));
    for(int i=0x3ffa;i<0x4000;i+=2) { rom[16+i]=0; rom[16+i+1]=0x80; }
    const char *path = "/data/local/tmp/nes-cheat-probe/probe.nes";
    FILE *file=fopen(path,"wb"); check(file != NULL,"create synthetic ROM");
    fwrite(rom,1,sizeof(rom),file); fclose(file);
    struct retro_game_info game = { .path=path };
    check(load(&game),"load synthetic ROM");
    run();
    unsigned char *ram = memory(RETRO_MEMORY_SYSTEM_RAM);
    check(ram && ram[0x200]==0x11 && ram[0x201]==0x11,"baseline RAM and CPU read");
    cheat(0,true,"0200:63"); run();
    check(ram[0x200]==0x11 && ram[0x201]==0x63,"legacy raw code substitutes reads");
    reset(); run();
    check(ram[0x201]==0x11,"reset removes substitution");
    cheat(0,true,"RAM:0200:55"); run();
    check(ram[0x200]==0x55 && ram[0x201]==0x55,"explicit RAM code writes actual memory");
    ram[0x200]=0x11; run();
    check(ram[0x200]==0x55,"RAM code reapplied on later frame");
    reset();
    ram[0x200]=0x11; run();
    check(ram[0x200]==0x11,"reset stops periodic RAM injection");
    check(ram[0x202]==0x78,"baseline ROM read");
    cheat(0,true,"AAAAAA"); run();
    check(ram[0x202]==0,"Game Genie substitution active");
    reset(); run();
    check(ram[0x202]==0x78,"reset removes Game Genie substitution");
    reset();
    cheat(0,true,"0000:63+0200:55"); run();
    check(ram[0x200]==0x11 && ram[0x201]==0x55,"mixed tokens do not inherit prior RAM-write type");
    reset(); unload();
    unsigned char rom246[16+32768+8192] = { 'N','E','S',26,2,1,0x62,0xF0 };
    const unsigned char program246[] = { 0x78,0xA9,0x11,0x8D,0x00,0x68,
        0xAD,0x00,0x68,0x8D,0x01,0x02,0x4C,0x06,0xE0 };
    memcpy(rom246+16+32768-8192,program246,sizeof(program246));
    for(int i=32768-6;i<32768;i+=2) { rom246[16+i]=0; rom246[16+i+1]=0xE0; }
    const char *path246="/data/local/tmp/nes-cheat-probe/probe246.nes";
    file=fopen(path246,"wb"); check(file != NULL,"create synthetic Mapper 246 ROM");
    fwrite(rom246,1,sizeof(rom246),file); fclose(file);
    game.path=path246;
    check(load(&game),"load synthetic Mapper 246 ROM");
    run(); ram=memory(RETRO_MEMORY_SYSTEM_RAM);
    unsigned char *sram=memory(RETRO_MEMORY_SAVE_RAM);
    check(sram && sram[0]==0x11 && ram[0x201]==0x11,"Mapper 246 baseline SRAM at CPU 6800");
    cheat(0,true,"RAM:6800:55"); run();
    check(sram[0]==0x55 && ram[0x201]==0x55,"Mapper 246 periodic code writes actual mapped SRAM");
    reset();
    sram[0]=0x11;
    cheat(0,true,"RAM:6000:66"); run();
    check(sram[0]==0x11 && ram[0x201]==0x11,"Mapper 246 register area is not falsely treated as SRAM");
    reset(); unload(); deinit(); dlclose(handle);
    puts("All isolated cheat checks passed; no application game data touched.");
    return 0;
}
