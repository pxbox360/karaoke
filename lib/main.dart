// แอพคาราโอเกะสำหรับ Android (Flutter)
// - แท็บ "เพลงของฉัน": แสดงวิดีโอในโฟลเดอร์ Karaoke, ค้นหา, กดเล่น, กดค้างเพื่อลบ
// - แท็บ "ค้นหา YouTube": ค้นหาเพลงแล้วกดดาวน์โหลดลงโฟลเดอร์ได้เลย
// - ปุ่มโฟลเดอร์มุมขวาบน: เปลี่ยนโฟลเดอร์เก็บเพลง

import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

const defaultFolder = '/storage/emulated/0/Karaoke';
const videoExts = ['.mp4', '.mkv', '.webm', '.3gp', '.mov', '.avi'];

String fileTitle(File f) {
  final name = f.uri.pathSegments.last;
  final dot = name.lastIndexOf('.');
  return dot > 0 ? name.substring(0, dot) : name;
}

String safeFileName(String s) =>
    s.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();

String fmtDuration(Duration? d) {
  if (d == null) return '';
  final m = d.inMinutes;
  final s = (d.inSeconds % 60).toString().padLeft(2, '0');
  return '$m:$s';
}

void main() => runApp(const KaraokeApp());

class KaraokeApp extends StatelessWidget {
  const KaraokeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'คาราโอเกะ',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: Colors.deepPurple,
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

// ---------------------------------------------------------------- หน้าหลัก

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int tab = 0;
  String folder = defaultFolder;
  List<File> songs = [];
  bool ready = false;
  String? error;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    folder = prefs.getString('folder') ?? defaultFolder;
    await _askPermission();
    await loadSongs();
    if (mounted) setState(() => ready = true);
  }

  Future<bool> _askPermission() async {
    // Android 11 ขึ้นไป: ขอสิทธิ์ "เข้าถึงไฟล์ทั้งหมด"
    if (await Permission.manageExternalStorage.isGranted) return true;
    if ((await Permission.manageExternalStorage.request()).isGranted) {
      return true;
    }
    // Android 10 ลงไป
    return (await Permission.storage.request()).isGranted;
  }

  Future<void> loadSongs() async {
    try {
      final dir = Directory(folder);
      if (!await dir.exists()) await dir.create(recursive: true);
      final files = dir
          .listSync()
          .whereType<File>()
          .where((f) => videoExts.any((e) => f.path.toLowerCase().endsWith(e)))
          .toList()
        ..sort((a, b) =>
            fileTitle(a).toLowerCase().compareTo(fileTitle(b).toLowerCase()));
      setState(() {
        songs = files;
        error = null;
      });
    } catch (e) {
      setState(() => error =
          'เปิดโฟลเดอร์ไม่ได้ ลองอนุญาตสิทธิ์เข้าถึงไฟล์ในการตั้งค่าแอพ\n($e)');
    }
  }

  Future<void> pickFolder() async {
    final path = await FilePicker.getDirectoryPath();
    if (path == null) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('folder', path);
    setState(() => folder = path);
    await loadSongs();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(tab == 0 ? 'เพลงของฉัน (${songs.length})' : 'ค้นหา YouTube'),
        actions: [
          IconButton(
            tooltip: 'เปลี่ยนโฟลเดอร์',
            icon: const Icon(Icons.folder_open),
            onPressed: pickFolder,
          ),
          IconButton(
            tooltip: 'รีเฟรช',
            icon: const Icon(Icons.refresh),
            onPressed: loadSongs,
          ),
        ],
      ),
      body: !ready
          ? const Center(child: CircularProgressIndicator())
          : IndexedStack(
              index: tab,
              children: [
                LibraryPage(
                  songs: songs,
                  folder: folder,
                  error: error,
                  onChanged: loadSongs,
                  onRetryPermission: () async {
                    await openAppSettings();
                  },
                ),
                YouTubePage(folder: folder, onDownloaded: loadSongs),
              ],
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.queue_music), label: 'เพลงของฉัน'),
          NavigationDestination(icon: Icon(Icons.search), label: 'ค้นหา YouTube'),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------- คลังเพลง

class LibraryPage extends StatefulWidget {
  final List<File> songs;
  final String folder;
  final String? error;
  final Future<void> Function() onChanged;
  final VoidCallback onRetryPermission;

  const LibraryPage({
    super.key,
    required this.songs,
    required this.folder,
    required this.error,
    required this.onChanged,
    required this.onRetryPermission,
  });

  @override
  State<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends State<LibraryPage> {
  String filter = '';

  Future<void> _delete(File f) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('ลบเพลงนี้?'),
        content: Text(fileTitle(f)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('ยกเลิก')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('ลบ')),
        ],
      ),
    );
    if (ok == true) {
      await f.delete();
      await widget.onChanged();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(widget.error!, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: widget.onRetryPermission,
              child: const Text('เปิดการตั้งค่าแอพ'),
            ),
          ]),
        ),
      );
    }

    final list = widget.songs
        .where((f) => fileTitle(f).toLowerCase().contains(filter.toLowerCase()))
        .toList();

    return Column(children: [
      Padding(
        padding: const EdgeInsets.all(12),
        child: TextField(
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search),
            hintText: 'ค้นหาเพลงในเครื่อง',
            border: OutlineInputBorder(),
          ),
          onChanged: (v) => setState(() => filter = v),
        ),
      ),
      Expanded(
        child: list.isEmpty
            ? Center(
                child: Text(
                  widget.songs.isEmpty
                      ? 'ยังไม่มีเพลงใน\n${widget.folder}\n\nไปที่แท็บ "ค้นหา YouTube" เพื่อดาวน์โหลด'
                      : 'ไม่พบเพลงที่ค้นหา',
                  textAlign: TextAlign.center,
                ),
              )
            : RefreshIndicator(
                onRefresh: widget.onChanged,
                child: ListView.builder(
                  itemCount: list.length,
                  itemBuilder: (c, i) {
                    final f = list[i];
                    return ListTile(
                      leading: const Icon(Icons.mic),
                      title: Text(fileTitle(f)),
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute(builder: (_) => PlayerPage(file: f)),
                      ),
                      onLongPress: () => _delete(f),
                    );
                  },
                ),
              ),
      ),
    ]);
  }
}

// ---------------------------------------------------------------- ตัวเล่นวิดีโอ

class PlayerPage extends StatefulWidget {
  final File file;
  const PlayerPage({super.key, required this.file});

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  late final VideoPlayerController ctrl;
  bool showControls = true;

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations(
        [DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    ctrl = VideoPlayerController.file(widget.file)
      ..initialize().then((_) {
        setState(() {});
        ctrl.play();
      })
      ..addListener(() {
        if (mounted) setState(() {});
      });
  }

  @override
  void dispose() {
    ctrl.dispose();
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final v = ctrl.value;
    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        onTap: () => setState(() => showControls = !showControls),
        child: Stack(children: [
          Center(
            child: v.isInitialized
                ? AspectRatio(aspectRatio: v.aspectRatio, child: VideoPlayer(ctrl))
                : const CircularProgressIndicator(),
          ),
          if (showControls) ...[
            Positioned(
              top: 8,
              left: 8,
              right: 8,
              child: Row(children: [
                IconButton(
                  icon: const Icon(Icons.arrow_back, color: Colors.white),
                  onPressed: () => Navigator.pop(context),
                ),
                Expanded(
                  child: Text(fileTitle(widget.file),
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 16)),
                ),
              ]),
            ),
            Center(
              child: IconButton(
                iconSize: 72,
                icon: Icon(
                  v.isPlaying ? Icons.pause_circle : Icons.play_circle,
                  color: Colors.white70,
                ),
                onPressed: () => v.isPlaying ? ctrl.pause() : ctrl.play(),
              ),
            ),
            Positioned(
              left: 16,
              right: 16,
              bottom: 16,
              child: Row(children: [
                Text(fmtDuration(v.position), style: const TextStyle(color: Colors.white)),
                const SizedBox(width: 8),
                Expanded(child: VideoProgressIndicator(ctrl, allowScrubbing: true)),
                const SizedBox(width: 8),
                Text(fmtDuration(v.duration), style: const TextStyle(color: Colors.white)),
              ]),
            ),
          ],
        ]),
      ),
    );
  }
}

// ---------------------------------------------------------------- ค้นหา/ดาวน์โหลด YouTube

class YouTubePage extends StatefulWidget {
  final String folder;
  final Future<void> Function() onDownloaded;

  const YouTubePage({super.key, required this.folder, required this.onDownloaded});

  @override
  State<YouTubePage> createState() => _YouTubePageState();
}

class _YouTubePageState extends State<YouTubePage>
    with AutomaticKeepAliveClientMixin {
  final yt = YoutubeExplode();
  final queryCtrl = TextEditingController();
  VideoSearchList? results;
  final List<Video> videos = [];
  bool searching = false;
  bool addKaraokeWord = true;
  final Map<String, double> progress = {}; // videoId -> 0.0..1.0
  final Set<String> done = {};

  @override
  bool get wantKeepAlive => true;

  @override
  void dispose() {
    yt.close();
    queryCtrl.dispose();
    super.dispose();
  }

  Future<void> search() async {
    var q = queryCtrl.text.trim();
    if (q.isEmpty) return;
    if (addKaraokeWord && !q.toLowerCase().contains('karaoke') && !q.contains('คาราโอเกะ')) {
      q = '$q karaoke';
    }
    FocusScope.of(context).unfocus();
    setState(() {
      searching = true;
      videos.clear();
    });
    try {
      results = await yt.search.search(q);
      videos.addAll(results!);
    } catch (e) {
      _snack('ค้นหาไม่สำเร็จ: $e');
    }
    if (mounted) setState(() => searching = false);
  }

  Future<void> loadMore() async {
    if (results == null) return;
    setState(() => searching = true);
    try {
      final next = await results!.nextPage();
      if (next != null) {
        results = next;
        videos.addAll(next);
      }
    } catch (e) {
      _snack('โหลดเพิ่มไม่สำเร็จ: $e');
    }
    if (mounted) setState(() => searching = false);
  }

  Future<void> download(Video v) async {
    final id = v.id.value;
    if (progress.containsKey(id)) return;
    setState(() => progress[id] = 0);
    IOSink? sink;
    File? file;
    try {
      final manifest = await yt.videos.streamsClient.getManifest(v.id);
      if (manifest.muxed.isEmpty) {
        throw Exception('ไม่มีไฟล์วิดีโอพร้อมเสียงสำหรับคลิปนี้');
      }
      final info = manifest.muxed.withHighestBitrate();
      final dir = Directory(widget.folder);
      if (!await dir.exists()) await dir.create(recursive: true);
      file = File('${widget.folder}/${safeFileName(v.title)}.${info.container.name}');

      final total = info.size.totalBytes;
      var got = 0;
      sink = file.openWrite();
      await for (final chunk in yt.videos.streamsClient.get(info)) {
        sink.add(chunk);
        got += chunk.length;
        if (mounted) setState(() => progress[id] = total > 0 ? got / total : 0);
      }
      await sink.flush();
      await sink.close();
      sink = null;
      done.add(id);
      _snack('ดาวน์โหลดเสร็จ: ${v.title}');
      await widget.onDownloaded();
    } catch (e) {
      await sink?.close();
      if (file != null && await file.exists()) await file.delete();
      _snack('ดาวน์โหลดไม่สำเร็จ: $e');
    }
    if (mounted) setState(() => progress.remove(id));
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Widget _trailing(Video v) {
    final id = v.id.value;
    if (progress.containsKey(id)) {
      return SizedBox(
        width: 40,
        height: 40,
        child: CircularProgressIndicator(value: progress[id] == 0 ? null : progress[id]),
      );
    }
    if (done.contains(id)) return const Icon(Icons.check_circle, color: Colors.green);
    return IconButton(
      tooltip: 'ดาวน์โหลด',
      icon: const Icon(Icons.download),
      onPressed: () => download(v),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
        child: TextField(
          controller: queryCtrl,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => search(),
          decoration: InputDecoration(
            hintText: 'ชื่อเพลง หรือ ศิลปิน',
            border: const OutlineInputBorder(),
            suffixIcon: IconButton(icon: const Icon(Icons.search), onPressed: search),
          ),
        ),
      ),
      SwitchListTile(
        dense: true,
        title: const Text('เติมคำว่า "karaoke" ให้อัตโนมัติ'),
        value: addKaraokeWord,
        onChanged: (v) => setState(() => addKaraokeWord = v),
      ),
      Expanded(
        child: videos.isEmpty
            ? Center(
                child: searching
                    ? const CircularProgressIndicator()
                    : const Text('พิมพ์ชื่อเพลงแล้วกดค้นหา'),
              )
            : ListView.builder(
                itemCount: videos.length + 1,
                itemBuilder: (c, i) {
                  if (i == videos.length) {
                    return Padding(
                      padding: const EdgeInsets.all(16),
                      child: Center(
                        child: searching
                            ? const CircularProgressIndicator()
                            : OutlinedButton(onPressed: loadMore, child: const Text('โหลดเพิ่ม')),
                      ),
                    );
                  }
                  final v = videos[i];
                  return ListTile(
                    leading: ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: Image.network(
                        v.thumbnails.mediumResUrl,
                        width: 100,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => const SizedBox(
                            width: 100, child: Icon(Icons.music_video)),
                      ),
                    ),
                    title: Text(v.title, maxLines: 2, overflow: TextOverflow.ellipsis),
                    subtitle: Text('${v.author}  •  ${fmtDuration(v.duration)}'),
                    trailing: _trailing(v),
                  );
                },
              ),
      ),
    ]);
  }
}
