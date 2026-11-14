import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../application/sleep_controller.dart';
import '../domain/sleep_timer.dart';

// DesktopSleepTimer: 15/30/45/60, custom 5..720, after track and cancel.
class SleepPanel extends ConsumerWidget {
  const SleepPanel({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(sleepProvider);
    if (!view.available) return const SizedBox.shrink();
    void apply(SleepRequest request) =>
        ref.read(sleepProvider.notifier).configure(request);
    final remaining = view.status.remainingMs;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 16),
        Text('Sleep timer', style: Theme.of(context).textTheme.titleMedium),
        if (view.status.afterTrack)
          const Text('After this track')
        else if (remaining != null)
          Text('${(remaining / 60000).ceil()} minutes remaining'),
        Wrap(
          spacing: 8,
          children: [
            for (final minutes in [15, 30, 45, 60])
              TextButton(
                onPressed: view.busy
                    ? null
                    : () => apply(SleepRequest.minutes(minutes)),
                child: Text('$minutes min'),
              ),
            TextButton(
              onPressed: view.busy
                  ? null
                  : () async {
                      final value = await showDialog<int>(
                        context: context,
                        builder: (context) => const _CustomMinutes(),
                      );
                      if (value != null && context.mounted)
                        apply(SleepRequest.minutes(value));
                    },
              child: const Text('Custom'),
            ),
            TextButton(
              onPressed: view.busy
                  ? null
                  : () => apply(const SleepRequest.afterTrack()),
              child: const Text('After this track'),
            ),
            if (view.status.active)
              TextButton(
                onPressed: view.busy
                    ? null
                    : () => apply(const SleepRequest.cancel()),
                child: const Text('Cancel timer'),
              ),
          ],
        ),
        if (view.error != null) Text(view.error!),
      ],
    );
  }
}

class _CustomMinutes extends StatefulWidget {
  const _CustomMinutes();
  @override
  State<_CustomMinutes> createState() => _CustomMinutesState();
}

class _CustomMinutesState extends State<_CustomMinutes> {
  final controller = TextEditingController();
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  String? error;
  void submit() {
    final minutes = int.tryParse(controller.text.trim());
    if (minutes == null || minutes < 5 || minutes > 720) {
      setState(() => error = 'Enter 5 to 720 minutes.');
      return;
    }
    Navigator.of(context).pop(minutes);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Sleep timer'),
    content: TextField(
      controller: controller,
      autofocus: true,
      keyboardType: TextInputType.number,
      decoration: InputDecoration(labelText: 'Minutes', errorText: error),
      onSubmitted: (_) => submit(),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Cancel'),
      ),
      TextButton(onPressed: submit, child: const Text('Start')),
    ],
  );
}
