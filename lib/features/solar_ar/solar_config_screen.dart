import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'solar_ar_native_service.dart';

/// Minimal rooftop design UI that opens the native AR screen via MethodChannel.
class SolarConfigScreen extends StatefulWidget {
  const SolarConfigScreen({super.key, this.arService});

  final SolarArNativeService? arService;

  @override
  State<SolarConfigScreen> createState() => _SolarConfigScreenState();
}

class _SolarConfigScreenState extends State<SolarConfigScreen> {
  late final SolarArNativeService _ar =
      widget.arService ?? SolarArNativeService();

  double _systemSizeKw = 3;
  int _panelPower = 500;
  String _alignment = '6x1';
  double _southHeightFt = 2;
  double _northHeightFt = 5;
  bool _opening = false;

  int get _panelCount => (_systemSizeKw.round() * 2).clamp(2, 10);

  Future<void> _openAr() async {
    if (_opening) return;
    setState(() => _opening = true);
    try {
      await _ar.openSolarAr(
        systemSize: _systemSizeKw,
        panelCount: _panelCount,
        panelPower: _panelPower,
        alignment: _alignment,
        southHeight: _southHeightFt,
        northHeight: _northHeightFt,
        units: 'ft',
        label: '${_systemSizeKw.round()} kW rooftop',
      );
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message ?? 'Unable to open AR')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Unable to open AR: $e')),
      );
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      backgroundColor: const Color(0xFF0B0F10),
      appBar: AppBar(
        backgroundColor: const Color(0xFF12181A),
        foregroundColor: Colors.white,
        title: const Text('Design rooftop'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 120),
        children: [
          Text(
            'Solar system',
            style: theme.textTheme.titleMedium?.copyWith(color: Colors.white),
          ),
          const SizedBox(height: 8),
          Text(
            'Choose a configuration, then tap View AR to open the native '
            'camera AR screen (no Google ARCore).',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: Colors.white70,
            ),
          ),
          const SizedBox(height: 24),
          _sectionLabel('System size'),
          Slider(
            value: _systemSizeKw,
            min: 2,
            max: 5,
            divisions: 3,
            label: '${_systemSizeKw.round()} kW',
            activeColor: const Color(0xFF1FA6A0),
            onChanged: (v) => setState(() => _systemSizeKw = v),
          ),
          Text(
            '${_systemSizeKw.round()} kW · $_panelCount panels',
            style: const TextStyle(color: Colors.white70),
          ),
          const SizedBox(height: 20),
          _sectionLabel('Alignment'),
          Wrap(
            spacing: 8,
            children: ['6x1', '3x2'].map((value) {
              final selected = _alignment == value;
              return ChoiceChip(
                label: Text(value),
                selected: selected,
                selectedColor: const Color(0xFF1FA6A0),
                labelStyle: TextStyle(
                  color: selected ? Colors.white : Colors.white70,
                ),
                onSelected: (_) => setState(() => _alignment = value),
              );
            }).toList(),
          ),
          const SizedBox(height: 20),
          _sectionLabel('Panel watts'),
          Slider(
            value: _panelPower.toDouble(),
            min: 300,
            max: 600,
            divisions: 6,
            label: '${_panelPower}W',
            activeColor: const Color(0xFF1FA6A0),
            onChanged: (v) => setState(() => _panelPower = v.round()),
          ),
          Text(
            '${_panelPower}W',
            style: const TextStyle(color: Colors.white70),
          ),
          const SizedBox(height: 20),
          _sectionLabel('South post height (ft)'),
          Slider(
            value: _southHeightFt,
            min: 1,
            max: 13,
            divisions: 24,
            label: _southHeightFt.toStringAsFixed(1),
            activeColor: const Color(0xFF1FA6A0),
            onChanged: (v) => setState(() => _southHeightFt = v),
          ),
          _sectionLabel('North post height (ft)'),
          Slider(
            value: _northHeightFt,
            min: 1,
            max: 13,
            divisions: 24,
            label: _northHeightFt.toStringAsFixed(1),
            activeColor: const Color(0xFF1FA6A0),
            onChanged: (v) => setState(() => _northHeightFt = v),
          ),
          const SizedBox(height: 12),
          Text(
            'Model: solar_3kw_6x1_rooftop.glb',
            style: theme.textTheme.bodySmall?.copyWith(color: Colors.white54),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
          child: SizedBox(
            height: 52,
            child: FilledButton(
              onPressed: _opening ? null : _openAr,
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF1FA6A0),
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              child: _opening
                  ? const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Text(
                      'View AR',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                    ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Text(
        text,
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}
