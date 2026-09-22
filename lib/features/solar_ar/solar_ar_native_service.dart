import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Opens the native Android Solar AR screen (CameraX + Filament, no ARCore).
class SolarArNativeService {
  SolarArNativeService({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel(_channelName);

  static const _channelName = 'com.example.ar_cust/solar_ar';

  final MethodChannel _channel;

  bool get isAndroid => defaultTargetPlatform == TargetPlatform.android;

  /// Launch native AR with optional rooftop design metadata.
  Future<void> openSolarAr({
    double? systemSize,
    int? panelCount,
    int? panelPower,
    String? alignment,
    double? width,
    double? length,
    double? southHeight,
    double? northHeight,
    double? slope,
    String? direction,
    String? units,
    String? label,
    String? glbPath,
    double? initialDistance,
  }) async {
    if (!isAndroid) {
      throw PlatformException(
        code: 'unsupported_platform',
        message: 'Custom Solar AR is Android-only for now.',
      );
    }

    await _channel.invokeMethod<void>('openSolarAr', <String, dynamic>{
      'systemSize': ?systemSize,
      'panelCount': ?panelCount,
      'panelPower': ?panelPower,
      'alignment': ?alignment,
      'width': ?width,
      'length': ?length,
      'southHeight': ?southHeight,
      'northHeight': ?northHeight,
      'slope': ?slope,
      'direction': ?direction,
      'units': ?units,
      'label': ?label,
      'glbPath': ?glbPath,
      'initialDistance': ?initialDistance,
    });
  }
}
