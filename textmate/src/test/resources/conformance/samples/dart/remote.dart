import 'dart:async';

/// A git remote.
class Remote {
  final String name;
  final Uri url;
  const Remote(this.name, this.url);

  @override
  String toString() => '$name -> ${url.host}';
}

Future<List<Remote>> loadRemotes(Stream<String> lines) async {
  final remotes = <Remote>[];
  await for (final line in lines) {
    final parts = line.split(RegExp(r'\s+'));
    if (parts.length < 2) continue; // malformed
    remotes.add(Remote(parts[0], Uri.parse(parts[1])));
  }
  return remotes..sort((a, b) => a.name.compareTo(b.name));
}
