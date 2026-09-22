import 'package:flutter_test/flutter_test.dart';

import 'package:ar_cust/main.dart';

void main() {
  testWidgets('Design rooftop screen loads with View AR', (WidgetTester tester) async {
    await tester.pumpWidget(const ArCustApp());

    expect(find.text('Design rooftop'), findsOneWidget);
    expect(find.text('View AR'), findsOneWidget);
  });
}
