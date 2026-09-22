import 'package:flutter/material.dart';

import 'features/solar_ar/solar_config_screen.dart';

void main() {
  runApp(const ArCustApp());
}

class ArCustApp extends StatelessWidget {
  const ArCustApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Solar AR Cust',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1FA6A0),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const SolarConfigScreen(),
    );
  }
}
