# Keep Firebase Firestore Models & App Data Classes
-keep class com.example.tab_able.data.Space { *; }
-keep class com.example.tab_able.data.SpaceExpense { *; }
-keep class com.example.tab_able.data.SpaceSettlement { *; }
-keep class com.example.tab_able.data.SpaceMessage { *; }
-keep class com.example.tab_able.data.Person { *; }
-keep class com.example.tab_able.data.Group { *; }
-keep class com.example.tab_able.data.Dispute { *; }
-keep class com.example.tab_able.data.Place { *; }

# Keep attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Maps
-keep class com.google.android.gms.maps.MapView { *; }
-keep class com.google.android.gms.maps.GoogleMap { *; }
-keep class com.google.android.gms.maps.SupportMapFragment { *; }
